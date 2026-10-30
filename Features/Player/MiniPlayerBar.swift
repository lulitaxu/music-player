import SwiftUI

/// Globally accessible mini-player (SPEC navigation). In Milestone 1 it
/// is an honest shell: it says nothing is playing and that the playback
/// engine lands in Phase 3. It never fakes a track or a working button.
public struct MiniPlayerBar: View {
    public init() {}
    public var body: some View {
        HStack(spacing: 12) {
            ArtworkView(size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text("Nothing playing")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppColors.ink)
                Text("Playback engine arrives in Phase 3")
                    .font(.caption)
                    .foregroundStyle(AppColors.inkSecondary)
            }
            Spacer()
            Image(systemName: "play.fill")
                .foregroundStyle(.tertiary)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .glassSurface(cornerRadius: 16)
        .padding(.horizontal, 12)
        .padding(.bottom, 4)
    }
}
