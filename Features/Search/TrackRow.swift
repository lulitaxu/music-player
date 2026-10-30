import SwiftUI

/// One search/home result row. The availability label is the app's
/// honesty spine made visible: playable, or metadata only.
public struct TrackRow: View {
    public let track: Track
    public let availability: AudioAvailability
    public let isCurrent: Bool

    public init(track: Track, availability: AudioAvailability, isCurrent: Bool = false) {
        self.track = track
        self.availability = availability
        self.isCurrent = isCurrent
    }

    public var body: some View {
        HStack(spacing: 12) {
            ArtworkView(url: track.artworkURL, size: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title)
                    .font(AppTypography.trackTitle)
                    .foregroundStyle(AppColors.ink)
                    .lineLimit(1)
                Text(track.artistName)
                    .font(AppTypography.caption)
                    .foregroundStyle(AppColors.inkSecondary)
                    .lineLimit(1)
            }
            Spacer()
            if isCurrent {
                Image(systemName: "speaker.wave.2.fill")
                    .foregroundStyle(AppColors.accent)
            } else {
                Text(label)
                    .font(AppTypography.caption)
                    .foregroundStyle(availability == .playable ? AppColors.accent : AppColors.inkSecondary)
            }
        }
        .padding(.vertical, 2)
    }

    private var label: String {
        switch availability {
        case .playable: return "Playable"
        case .downloadable: return "Downloadable"
        case .metadataOnly: return "Metadata only"
        case .local: return "On device"
        }
    }
}
