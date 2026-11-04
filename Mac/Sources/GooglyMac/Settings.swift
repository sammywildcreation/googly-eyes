import Foundation
import GooglyShared

/// User choices from the menu bar, kept between launches.
final class Settings {
    static let shared = Settings()
    private let defaults = UserDefaults.standard

    var onChange: (() -> Void)?

    /// Cursor size in points (the design's default is 72).
    var cursorSize: Double {
        get { defaults.object(forKey: "cursorSize") as? Double ?? 72 }
        set { defaults.set(newValue, forKey: "cursorSize"); onChange?() }
    }

    var showCursor: Bool {
        get { defaults.object(forKey: "showCursor") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "showCursor"); onChange?() }
    }

    /// Where the phone sits under the screen, 0 = left edge, 1 = right edge.
    var phonePosition: Double {
        get { defaults.object(forKey: "phonePosition") as? Double ?? 0.5 }
        set { defaults.set(newValue, forKey: "phonePosition"); onChange?() }
    }

    /// When idle, hide the big cursor and let the phone's eyes follow your own mouse.
    var followMouse: Bool {
        get { defaults.object(forKey: "followMouse") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "followMouse"); onChange?() }
    }

    var captions: Bool {
        get { defaults.object(forKey: "captions") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "captions"); onChange?() }
    }

    /// Lets him click, type, press keys and open things when you ask.
    var computerControl: Bool {
        get { defaults.object(forKey: "computerControl") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "computerControl"); onChange?() }
    }

    /// How the cursor's path shows while it flies.
    var trail: PointerTrail {
        get { PointerTrail(rawValue: defaults.string(forKey: "trail") ?? "") ?? .comet }
        set { defaults.set(newValue.rawValue, forKey: "trail"); onChange?() }
    }

    /// The mood picked by hand in the menu.
    var mood: Mood {
        get { Mood(rawValue: defaults.string(forKey: "mood") ?? "") ?? .listening }
        set { defaults.set(newValue.rawValue, forKey: "mood"); onChange?() }
    }

    // MARK: Shared with the phone (name, color, silent mode, focus mode)

    /// Called after the name, color, silent mode or focus mode changes, from the menu or the phone.
    var onPrefsChange: (() -> Void)?

    /// What he's called. Shown in menus, on the phone and in his instructions.
    var name: String {
        get { CompanionPrefs.clean(defaults.string(forKey: "companionName") ?? "Bluey") }
        set { defaults.set(CompanionPrefs.clean(newValue), forKey: "companionName"); prefsChanged() }
    }

    /// His color as a hue in degrees, or nil for the original blueberry.
    var hue: Double? {
        get { defaults.object(forKey: "companionHue") as? Double }
        set {
            if let newValue, newValue.isFinite { defaults.set(min(max(newValue, 0), 360), forKey: "companionHue") }
            else { defaults.removeObject(forKey: "companionHue") }
            Palette.hue = hue
            prefsChanged()
        }
    }

    /// Silent mode: nothing shows on the Mac (no cursor, bubbles or cards). He still looks at the screen
    /// and acts when asked, and every answer appears only on the phone.
    var silentMode: Bool {
        get { defaults.bool(forKey: "silentMode") }
        set { defaults.set(newValue, forKey: "silentMode"); prefsChanged() }
    }

    /// Focus mode: the timer and periodic screen checks. Off by default; turn it on when you want it.
    var focusEnabled: Bool {
        get { defaults.bool(forKey: "focusEnabled") }
        set { defaults.set(newValue, forKey: "focusEnabled"); prefsChanged() }
    }

    var prefs: CompanionPrefs {
        CompanionPrefs(name: name, hue: hue, silent: silentMode, focusEnabled: focusEnabled)
    }

    /// Applies a change sent from the phone. Only the fields present in the JSON change.
    func applyPrefs(json: [String: Any]) {
        if let name = json["name"] as? String { defaults.set(CompanionPrefs.clean(name), forKey: "companionName") }
        if json.keys.contains("hue") {
            if let hue = (json["hue"] as? NSNumber)?.doubleValue, hue.isFinite { defaults.set(min(max(hue, 0), 360), forKey: "companionHue") }
            else { defaults.removeObject(forKey: "companionHue") }
            Palette.hue = hue
        }
        if let silent = json["silent"] as? Bool { defaults.set(silent, forKey: "silentMode") }
        if let focus = json["focusEnabled"] as? Bool { defaults.set(focus, forKey: "focusEnabled") }
        prefsChanged()
    }

    private func prefsChanged() {
        onChange?()
        onPrefsChange?()
    }

    private init() {
        Palette.hue = defaults.object(forKey: "companionHue") as? Double
    }
}
