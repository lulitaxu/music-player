import SwiftUI

/// Translucent surface used by the mini-player and floating chrome.
///
/// iOS 18 path (the target device's only path, since the XS never runs
/// newer OS versions): SwiftUI Material. On OS versions with native
/// Liquid Glass APIs, a later phase adds an availability-branched
/// glassEffect implementation behind this same modifier, so call sites
/// never change.
public struct GlassSurface: ViewModifier {
    public var cornerRadius: CGFloat = 20
    public func body(content: Content) -> some View {
        content
            .background(.ultraThinMaterial,
                        in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(AppColors.hairline, lineWidth: 1)
            )
    }
}

public extension View {
    func glassSurface(cornerRadius: CGFloat = 20) -> some View {
        modifier(GlassSurface(cornerRadius: cornerRadius))
    }
}
