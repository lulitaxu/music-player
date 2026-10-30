import SwiftUI

/// Design tokens, dark-first per SPEC. One accent, used sparingly.
/// (Deliberately not Nyktos lime or any other product's brand color:
/// products keep separate identities.)
public enum AppColors {
    public static let background = Color(red: 0.039, green: 0.039, blue: 0.047)   // #0A0A0C
    public static let surface = Color(red: 0.09, green: 0.09, blue: 0.106)        // #17171B
    public static let ink = Color(red: 0.949, green: 0.949, blue: 0.941)          // #F2F2F0
    public static let inkSecondary = Color(red: 0.604, green: 0.604, blue: 0.639) // #9A9AA3
    public static let accent = Color(red: 1.0, green: 0.36, blue: 0.24)           // vermilion
    public static let hairline = Color.white.opacity(0.08)
}
