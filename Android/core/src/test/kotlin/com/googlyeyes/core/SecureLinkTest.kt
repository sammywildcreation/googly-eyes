package com.googlyeyes.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecureLinkTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test fun hkdfMatchesRfc5869TestCase1() {
        val okm = hkdfSha256(ByteArray(22) { 0x0b }, hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.hex())
    }

    @Test fun hmacMatchesRfc4231TestCase2() {
        val mac = hmacSha256("Jefe".toByteArray(), "what do ya want for nothing?".toByteArray())
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", mac.hex())
    }

    /** Wires a phone and a Mac LineSession together in memory and checks a full handshake and both directions. */
    @Test fun loopbackHandshakeAndPackets() {
        val secret = ByteArray(32) { it.toByte() }
        lateinit var phone: LineSession
        lateinit var mac: LineSession
        mac = LineSession(secret, server = true) { phone.receive(it) }
        phone = LineSession(secret, server = false) { mac.receive(it) }
        val phoneGot = mutableListOf<Packet>()
        val macGot = mutableListOf<Packet>()
        var phoneAuthed = false
        phone.onAuthenticated = { phoneAuthed = true }
        phone.onPacket = { phoneGot += it }
        mac.onPacket = { macGot += it }
        mac.onAuthenticated = { mac.send(Packet(hello = "Mac")) }
        mac.startServer()
        assertTrue(phoneAuthed)
        assertTrue(mac.authenticated)
        phone.send(Packet(command = "tool", tool = "look_at_screen", text = "{}", callID = "1"))
        mac.send(Packet(face = FaceState(0.5, -0.25, Mood.HAPPY, 0.1)))
        assertEquals("Mac", phoneGot[0].hello)
        assertEquals(Mood.HAPPY, phoneGot[1].face?.mood)
        assertEquals("look_at_screen", macGot.single().tool)
    }

    @Test fun wrongSecretIsRejected() {
        lateinit var phone: LineSession
        lateinit var mac: LineSession
        mac = LineSession(ByteArray(32) { 1 }, server = true) { phone.receive(it) }
        phone = LineSession(ByteArray(32) { 2 }, server = false) { mac.receive(it) }
        var failure: String? = null
        mac.onFailure = { failure = it }
        mac.startServer()
        assertEquals("wrong pairing secret", failure)
        assertFalse(phone.authenticated)
    }

    @Test fun tamperedAndReplayedRecordsAreRejected() {
        val secret = ByteArray(32) { 7 }
        val challenge = ByteArray(32) { 9 }
        val mac = SecureChannel(secret, challenge, server = true)
        val phone = SecureChannel(secret, challenge, server = false)
        val first = mac.seal(Packet(command = "a"))
        val second = mac.seal(Packet(command = "b"))
        assertEquals("a", phone.open(first).command)
        assertTrue(runCatching { phone.open(first) }.isFailure, "replay must fail")
        val bytes = Base64.getDecoder().decode(second)
        bytes[20] = (bytes[20].toInt() xor 1).toByte()
        assertTrue(runCatching { phone.open(Base64.getEncoder().encode(bytes)) }.isFailure, "tamper must fail")
    }

    @Test fun recordJsonMatchesSwiftShape() {
        // Swift's JSONEncoder leaves nil optionals out; Kotlin must do the same.
        val json = WireJson.encodeToString(Packet.serializer(), Packet(command = "awake"))
        assertEquals("""{"command":"awake"}""", json)
        val face = WireJson.encodeToString(Packet.serializer(), Packet(face = FaceState()))
        assertEquals("""{"face":{"gazeX":0.0,"gazeY":0.0,"mood":"listening","talk":0.0}}""", face)
        // Swift writes whole doubles without ".0" and escapes slashes; both must decode.
        val decoded = WireJson.decodeFromString(Packet.serializer(), """{"face":{"gazeX":0,"gazeY":1,"mood":"pointing","talk":0},"text":"a\/b","extra":1}""")
        assertEquals(Mood.POINTING, decoded.face?.mood)
        assertEquals("a/b", decoded.text)
    }

    @Test fun pairingQrFromSwiftParses() {
        val secret = ByteArray(32) { (it * 7).toByte() }
        val b64 = Base64.getEncoder().encodeToString(secret).replace("/", "\\/")
        val swiftJson = """{"version":1,"service":"Sam’s MacBook","secret":"$b64"}"""
        val qr = PairingRecord.PREFIX + Base64.getEncoder().encodeToString(swiftJson.toByteArray())
        val record = assertNotNull(PairingRecord.parse(qr))
        assertTrue(record.key!!.contentEquals(secret))
        assertEquals(sha256(secret).copyOf(12).hex(), record.identifier)
        assertEquals(24, record.identifier.length)
        assertNull(PairingRecord.parse("bluey-pair:nonsense"))
        assertNull(PairingRecord.parse("https://example.com"))
        assertEquals(record, PairingRecord.parse(record.qrText))
    }

    @Test fun prefsAndFocusDecodeFromSwift() {
        val prefs = WireJson.decodeFromString(CompanionPrefs.serializer(), """{"name":"Max","silent":true,"focusEnabled":false}""")
        assertEquals(CompanionPrefs("Max", null, true, false), prefs)
        val sync = WireJson.decodeFromString(FocusSync.serializer(), """
            {"current":{"id":"6B1F","task":"Edit video","duration":1800,"startedAt":783000000.5,"elapsed":90,
             "status":"running","detail":"ok","checks":[{"id":"x","elapsed":30,"activity":"on_task","probability":0.1,
             "confidence":0.9,"nudged":false},{"id":"y","elapsed":60,"activity":"off_task","probability":0.8,"confidence":0.7,"nudged":true}]},
             "history":[],"revision":4,"sentAt":783000100}
        """.trimIndent())
        val current = assertNotNull(sync.current)
        assertEquals(FocusStatus.RUNNING, current.status)
        assertEquals("28:30", FocusSession.clock(current.remaining))
        assertEquals(0.5, current.onTaskShare)
        // 783000000.5 s after 2001-01-01 is in October 2025.
        assertEquals(2025, java.time.Instant.ofEpochMilli(current.startedAtMillis).atZone(java.time.ZoneOffset.UTC).year)
    }

    @Test fun handshakeLinesArePlainJson() {
        var line = ""
        val mac = LineSession(ByteArray(32), server = true) { line = String(it) }
        mac.startServer()
        assertTrue(line.endsWith("\n"))
        val obj = Json.parseToJsonElement(line.trim()).jsonObject
        assertEquals(32, Base64.getDecoder().decode(obj["challenge"].toString().trim('"')).size)
    }
}
