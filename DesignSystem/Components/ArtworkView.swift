import SwiftUI

/// Album artwork tile. Loads remote artwork when a URL exists
/// (Nuke-backed pipeline replaces AsyncImage in Phase 2); otherwise an
/// honest graphite placeholder, never a fake cover.
public struct ArtworkView: View {
    public let url: URL?
    public let size: CGFloat
    public init(url: URL? = nil, size: CGFloat) {
        self.url = url; self.size = size
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
    }
}
