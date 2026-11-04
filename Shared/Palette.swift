import Foundation

/// Character colors as 0xRRGGBB. The original blueberry palette is the default; picking another
/// color rotates every tinted shade to that hue, so the gradient keeps its light-to-deep look.
public enum Palette {
    // The original blueberry shades, before any color choice is applied.
    private static let originalBerry1: UInt32 = 0xA9BCFF  // light periwinkle
    private static let originalBerry2: UInt32 = 0x6C86F5
    private static let originalBerry3: UInt32 = 0x4254D6
    private static let originalBerry4: UInt32 = 0x2B2F8F  // deep indigo
    private static let originalNose: UInt32 = 0x1C1F66
    private static let originalTint: UInt32 = 0xEEF0FF

    /// The hue (degrees) of the original blueberry's main shade.
    public static let originalHue: Double = hsv(originalBerry2).h

    /// The chosen character hue in degrees (0–360), or nil for the original blueberry.
    public static var hue: Double?

    public static var berry1: UInt32 { tinted(originalBerry1) }
    public static var berry2: UInt32 { tinted(originalBerry2) }
    public static var berry3: UInt32 { tinted(originalBerry3) }
    public static var berry4: UInt32 { tinted(originalBerry4) }
    public static var nose: UInt32 { tinted(originalNose) }
    /// A very light wash of the character color (speech bubble bottoms).
    public static var tint: UInt32 { tinted(originalTint) }
    public static let ink: UInt32 = 0x17151F
    public static let inkSoft: UInt32 = 0xB9B2CC
    public static let panel: UInt32 = 0x1E1B29

    public static var gradient: [UInt32] { [berry1, berry2, berry3, berry4] }
    public static let gradientStops: [Double] = [0, 0.34, 0.68, 1]

    /// Ready-made color choices. A nil hue is the original blueberry.
    public static let presets: [(name: String, hue: Double?)] = [
        ("Blue", nil), ("Purple", 268), ("Pink", 322), ("Red", 354), ("Orange", 24),
        ("Yellow", 46), ("Green", 138), ("Teal", 176),
    ]

    public static func presetName(for hue: Double?) -> String {
        presets.first { $0.hue == hue }?.name ?? "Custom"
    }

    public static func rgb(_ hex: UInt32) -> (r: Double, g: Double, b: Double) {
        (Double((hex >> 16) & 0xFF) / 255, Double((hex >> 8) & 0xFF) / 255, Double(hex & 0xFF) / 255)
    }

    /// Rotates a shade from the original palette to the chosen hue, keeping its saturation and brightness.
    public static func tinted(_ hex: UInt32) -> UInt32 {
        guard let hue else { return hex }
        var c = hsv(hex)
        c.h = (c.h + hue - originalHue).truncatingRemainder(dividingBy: 360)
        if c.h < 0 { c.h += 360 }
        return fromHSV(c.h, c.s, c.v)
    }

    static func hsv(_ hex: UInt32) -> (h: Double, s: Double, v: Double) {
        let (r, g, b) = rgb(hex)
        let mx = max(r, g, b), mn = min(r, g, b), d = mx - mn
        var h = 0.0
        if d > 0 {
            if mx == r { h = 60 * ((g - b) / d).truncatingRemainder(dividingBy: 6) }
            else if mx == g { h = 60 * ((b - r) / d + 2) }
            else { h = 60 * ((r - g) / d + 4) }
        }
        if h < 0 { h += 360 }
        return (h, mx == 0 ? 0 : d / mx, mx)
    }

    static func fromHSV(_ h: Double, _ s: Double, _ v: Double) -> UInt32 {
        let c = v * s, x = c * (1 - abs((h / 60).truncatingRemainder(dividingBy: 2) - 1)), m = v - c
        let (r, g, b): (Double, Double, Double)
        switch h {
        case ..<60: (r, g, b) = (c, x, 0)
        case ..<120: (r, g, b) = (x, c, 0)
        case ..<180: (r, g, b) = (0, c, x)
        case ..<240: (r, g, b) = (0, x, c)
        case ..<300: (r, g, b) = (x, 0, c)
        default: (r, g, b) = (c, 0, x)
        }
        func byte(_ n: Double) -> UInt32 { UInt32(max(0, min(255, ((n + m) * 255).rounded()))) }
        return byte(r) << 16 | byte(g) << 8 | byte(b)
    }
}

/// The user's choices that both apps share: his name, his color, silent mode and focus mode.
/// The Mac keeps the authoritative copy and sends it to the phone as JSON in a "prefs" packet.
public struct CompanionPrefs: Codable, Equatable, Sendable {
    public var name: String
    /// Hue in degrees, or nil for the original blueberry.
    public var hue: Double?
    /// Silent mode: nothing appears on the Mac; answers show only on the phone.
    public var silent: Bool
    /// Focus mode (timer plus periodic screen checks). Off unless the user turns it on.
    public var focusEnabled: Bool

    public init(name: String = "Bluey", hue: Double? = nil, silent: Bool = false, focusEnabled: Bool = false) {
        self.name = name; self.hue = hue; self.silent = silent; self.focusEnabled = focusEnabled
    }

    /// Names are shown everywhere and go into his instructions, so keep them short and plain.
    public static func clean(_ name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
            .filter { !$0.isNewline && $0 != "\"" }
        return trimmed.isEmpty ? "Bluey" : String(trimmed.prefix(24))
    }
}
