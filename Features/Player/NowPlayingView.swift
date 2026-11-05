import SwiftUI

/// Full Now Playing: artwork, honest progress with seek, transport
/// controls, and the live queue. Presented from the mini-player.
/// (Live Art layers land on this screen in the polish layer.)
public struct NowPlayingView: View {
    @EnvironmentObject private var engine: PlaybackEngine
    @Environment(\.dismiss) private var dismiss

    public init() {}

    public var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                if let track = engine.currentTrack {
                    ArtworkView(url: track.artworkURL, size: 280)
                        .padding(.top, 12)
                    VStack(spacing: 4) {
                        Text(track.title)
                            .font(.title3.weight(.bold))
                            .foregroundStyle(AppColors.ink)
                            .multilineTextAlignment(.center)
                            .lineLimit(2)
                        Text(track.artistName)
                            .font(AppTypography.body)
                            .foregroundStyle(AppColors.inkSecondary)
                    }
                    progress
                    transport
                } else {
                    EmptyStateView(
                        systemImage: "music.note",
                        title: "Nothing playing",
                        message: "Play a song from Home or Search and it will live here."
                    )
                }
                if !engine.queue.isEmpty {
                    queueList
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 20)
            .background(AppColors.background)
            .navigationTitle("Now Playing")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private var progress: some View {
        VStack(spacing: 4) {
            Slider(
                value: Binding(
                    get: { engine.currentTime },
                    set: { engine.seek(to: $0) }
                ),
                in: 0...max(engine.duration, 1)
            )
            .tint(AppColors.accent)
            HStack {
                Text(Self.format(engine.currentTime))
                Spacer()
                Text(Self.format(engine.duration))
            }
            .font(AppTypography.mono)
            .foregroundStyle(AppColors.inkSecondary)
        }
    }

    private var transport: some View {
        HStack(spacing: 44) {
            Button { engine.previous() } label: {
                Image(systemName: "backward.fill").font(.title2)
            }
            .accessibilityLabel("Previous track")
            Button { engine.togglePlayPause() } label: {
                Image(systemName: engine.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                    .font(.system(size: 56))
            }
            .accessibilityLabel(engine.isPlaying ? "Pause" : "Play")
            Button { engine.next() } label: {
                Image(systemName: "forward.fill").font(.title2)
            }
            .accessibilityLabel("Next track")
        }
        .foregroundStyle(AppColors.ink)
        .buttonStyle(.plain)
    }

    private var queueList: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Up next")
                .font(AppTypography.sectionTitle)
                .foregroundStyle(AppColors.ink)
            List {
                ForEach(Array(engine.queue.enumerated().filter { $0.offset > engine.currentIndex }), id: \.element.id) { index, track in
                    Button { engine.playAt(index: index) } label: {
                        TrackRow(track: track, availability: .playable,
                                 isCurrent: index == engine.currentIndex)
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(Color.clear)
                }
            }
            .listStyle(.plain)
            .frame(maxHeight: 220)
        }
    }

    private static func format(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "0:00" }
        let total = Int(seconds)
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}
