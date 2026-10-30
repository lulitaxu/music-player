import SwiftUI

/// Type scale tokens. System fonts only in Milestone 1; the Phase 1
/// design pass may refine, but every screen must use these tokens
/// rather than ad-hoc sizes.
public enum AppTypography {
    public static let screenTitle = Font.largeTitle.weight(.bold)
    public static let sectionTitle = Font.title3.weight(.semibold)
    public static let trackTitle = Font.body.weight(.semibold)
    public static let body = Font.body
    public static let caption = Font.caption
    public static let mono = Font.caption.monospaced()
}
