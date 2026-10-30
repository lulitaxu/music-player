import SwiftUI

/// Motion tokens: the app's animation vocabulary, defined once so every
/// screen moves the same way (Apple Music-style fluidity comes from
/// consistent spring physics, not from random effects per screen).
///
/// Rules: springs for anything spatial (sheets, artwork, mini-player
/// expansion); no gratuitous looping animation; every animation must
/// respect Reduce Motion at the call site. Lyrics sync uses the
/// playback clock, never these tokens (SPEC lyrics section).
public enum Motion {
    /// Quick, confident: tab chrome, small controls.
    public static let snappy = Animation.spring(response: 0.32, dampingFraction: 0.86)
    /// The signature move: Now Playing artwork and sheet transitions.
    public static let fluid = Animation.spring(response: 0.5, dampingFraction: 0.92)
    /// Slow fades for ambient, artwork-color background shifts.
    public static let gentle = Animation.spring(response: 0.7, dampingFraction: 1.0)
    /// Karaoke active-line emphasis (visual only; timing is the player's).
    public static let lyricEmphasis = Animation.easeInOut(duration: 0.35)
}
