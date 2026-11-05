import SwiftUI

/// Album artwork tile. Loads remote artwork when a URL exists
/// (Nuke-backed pipeline replaces AsyncImage in Phase 2); otherwise an
/// honest graphite placeholder, never a fake cover.
public struct ArtworkView: View {
    public let url: URL?
    public let size: CGFloat
    public let animated: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isBreathing = false

    public init(url: URL? = nil, size: CGFloat, animated: Bool = false) {
        self.url = url
        self.size = size
        self.animated = animated
    }
    public var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.12, style: .continuous)
                .fill(AppColors.surface)
            if let url {
                AsyncImage(url: url) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    Image(systemName: "music.note")
                        .foregroundStyle(AppColors.inkSecondary)
                }
            } else {
                Image(systemName: "music.note")
                    .foregroundStyle(AppColors.inkSecondary)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size * 0.12, style: .continuous))
        .scaleEffect(animated && !reduceMotion && isBreathing ? 1.025 : 1)
        .animation(
            animated && !reduceMotion
                ? .easeInOut(duration: 3).repeatForever(autoreverses: true)
                : nil,
            value: isBreathing
        )
        .onAppear {
            if animated && !reduceMotion { isBreathing = true }
        }
    }
}
