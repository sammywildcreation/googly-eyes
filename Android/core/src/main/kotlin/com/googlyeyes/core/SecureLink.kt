package com.googlyeyes.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * What the Mac's pairing QR code holds: `bluey-pair:` + base64(JSON {version, service, secret}).
 * The secret is a 32-byte key; treat it like a password.
 */
@Serializable
data class PairingRecord(
    val version: Int = 1,
    /** The Mac's Bonjour name, for display. */
    val service: String,
    /** Base64 of the 32-byte pairing secret. */
    val secret: String,
) {
    val key: ByteArray?
        get() = runCatching { Base64.getDecoder().decode(secret) }.getOrNull()?.takeIf { it.size == 32 }

    /** Public fingerprint the Mac advertises in its Bonjour TXT record, so the phone finds the right Mac. */
    val identifier: String
        get() = key?.let { sha256(it).copyOf(12).joinToString("") { b -> "%02x".format(b) } } ?: "invalid"

    val qrText: String
        get() = PREFIX + Base64.getEncoder().encodeToString(WireJson.encodeToString(serializer(), this).toByteArray())

    companion object {
        const val PREFIX = "bluey-pair:"

        fun parse(text: String): PairingRecord? {
            val trimmed = text.trim()
            if (!trimmed.startsWith(PREFIX) || trimmed.length >= 2048) return null
            return runCatching {
                val json = String(Base64.getDecoder().decode(trimmed.removePrefix(PREFIX)))
                WireJson.decodeFromString(serializer(), json)
            }.getOrNull()?.takeIf { it.version == 1 && it.key != null && it.service.isNotEmpty() }
        }
    }
}

internal fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        doFinal(data)
    }

/** HKDF-SHA256 (RFC 5869), matching CryptoKit's `HKDF<SHA256>.deriveKey`. */
internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val prk = hmacSha256(salt, ikm)
    val out = java.io.ByteArrayOutputStream()
    var previous = ByteArray(0)
    var counter = 1
    while (out.size() < length) {
        previous = hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))
        out.write(previous)
        counter++
    }
    return out.toByteArray().copyOf(length)
}

/**
 * The encrypted channel, identical to Shared/SecurePairing.swift's `SecureChannel`:
 * direction keys from HKDF(secret, salt = challenge, info = "bluey-v1-<direction>"), and each line is
 * base64(AES-GCM combined = 12-byte nonce | ciphertext | 16-byte tag) of JSON {"sequence": n, "packet": {...}}.
 * Sequence numbers must arrive in order, which stops replays.
 */
class SecureChannel(secret: ByteArray, challenge: ByteArray, server: Boolean) {
    private val sendKey = key(secret, challenge, if (server) "mac-to-phone" else "phone-to-mac")
    private val receiveKey = key(secret, challenge, if (server) "phone-to-mac" else "mac-to-phone")
    private var sent = 0
    private var received = 0
    private val random = SecureRandom()

    @Serializable
    private data class Record(val sequence: Int, val packet: Packet)

    fun seal(packet: Packet): ByteArray {
        val json = WireJson.encodeToString(Record.serializer(), Record(sent, packet)).toByteArray()
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey, "AES"), GCMParameterSpec(128, nonce))
        val combined = nonce + cipher.doFinal(json)
        sent++
        return Base64.getEncoder().encode(combined)
    }

    fun open(line: ByteArray): Packet {
        val combined = Base64.getDecoder().decode(line)
        require(combined.size >= 28) { "record too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(receiveKey, "AES"), GCMParameterSpec(128, combined, 0, 12))
        val json = String(cipher.doFinal(combined, 12, combined.size - 12))
        val record = WireJson.decodeFromString(Record.serializer(), json)
        require(record.sequence == received) { "out of order" }
        received++
        return record.packet
    }

    companion object {
        private val PAIR_LABEL = "bluey-pair-v1".toByteArray()

        private fun key(secret: ByteArray, challenge: ByteArray, direction: String) =
            hkdfSha256(secret, challenge, "bluey-v1-$direction".toByteArray(), 32)

        fun proof(secret: ByteArray, challenge: ByteArray): ByteArray = hmacSha256(secret, PAIR_LABEL + challenge)

        fun verifies(proof: ByteArray, secret: ByteArray, challenge: ByteArray): Boolean =
            MessageDigest.isEqual(proof, proof(secret, challenge))
    }
}

/**
 * The newline-delimited protocol over one TCP connection, without any networking of its own, so it can be
 * tested and reused. Feed it received bytes with [receive]; it hands lines to write to [write].
 *
 * Handshake (same as Shared/GooglyLink.swift `LineConnection`):
 * 1. Mac (server) sends plain JSON `{"challenge": base64(32 random bytes)}`.
 * 2. Phone (client) answers plain JSON `{"proof": base64(HMAC(secret, "bluey-pair-v1" + challenge))}`.
 * 3. Mac sends the first encrypted packet, `command = "paired"`. From then on every line is encrypted.
 */
class LineSession(
    private val secret: ByteArray,
    private val server: Boolean,
    private val write: (ByteArray) -> Unit,
) {
    var onAuthenticated: (() -> Unit)? = null
    var onPacket: ((Packet) -> Unit)? = null
    /** Something was wrong (bad proof, tampering, garbage). The connection should be closed. */
    var onFailure: ((String) -> Unit)? = null

    var authenticated = false
        private set
    private var failed = false
    private var challenge: ByteArray? = null
    private var channel: SecureChannel? = null
    private val buffer = java.io.ByteArrayOutputStream()

    /** Server side only: call when the TCP connection is up. */
    fun startServer() {
        check(server)
        val c = ByteArray(32).also { SecureRandom().nextBytes(it) }
        challenge = c
        handshake("challenge", c)
    }

    fun send(packet: Packet) {
        val channel = channel ?: return
        if (!authenticated || failed) return
        writeLine(channel.seal(packet))
    }

    fun receive(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        if (failed) return
        buffer.write(bytes, offset, length)
        if (buffer.size() > 16 * 1024 * 1024) { fail("too much data"); return }
        // Take each complete line out of the buffer before handling it, so a reply that arrives while
        // a line is being handled (re-entrantly) is simply queued behind it.
        while (!failed) {
            val data = buffer.toByteArray()
            val newline = data.indexOf(0x0A.toByte())
            if (newline < 0) break
            buffer.reset()
            buffer.write(data, newline + 1, data.size - newline - 1)
            accept(data.copyOfRange(0, newline))
        }
    }

    private fun accept(line: ByteArray) {
        val channel = channel
        if (channel != null) {
            val packet = try { channel.open(line) } catch (e: Exception) { fail("bad record: ${e.message}"); return }
            if (!authenticated) {
                if (server || packet.command != "paired") { fail("expected paired"); return }
                authenticated = true
                onAuthenticated?.invoke()
            } else {
                onPacket?.invoke(packet)
            }
            return
        }
        val fields: JsonObject = try {
            WireJson.parseToJsonElement(String(line)).jsonObject
        } catch (e: Exception) { fail("bad handshake"); return }
        fun field(name: String): ByteArray? =
            (fields[name] as? JsonPrimitive)?.contentOrNull?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        if (server) {
            val challenge = challenge ?: run { fail("no challenge"); return }
            val proof = field("proof")
            if (proof == null || !SecureChannel.verifies(proof, secret, challenge)) { fail("wrong pairing secret"); return }
            this.channel = SecureChannel(secret, challenge, server = true)
            authenticated = true
            send(Packet(command = "paired"))
            onAuthenticated?.invoke()
        } else {
            val challenge = field("challenge")
            if (challenge == null || challenge.size != 32) { fail("bad challenge"); return }
            this.channel = SecureChannel(secret, challenge, server = false)
            handshake("proof", SecureChannel.proof(secret, challenge))
        }
    }

    private fun handshake(name: String, value: ByteArray) {
        val json = JsonObject(mapOf(name to JsonPrimitive(Base64.getEncoder().encodeToString(value))))
        writeLine(json.toString().toByteArray())
    }

    private fun writeLine(data: ByteArray) = write(data + 0x0A.toByte())

    private fun fail(reason: String) {
        if (failed) return
        failed = true
        onFailure?.invoke(reason)
    }
}
