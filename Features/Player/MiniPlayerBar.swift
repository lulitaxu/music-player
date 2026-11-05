import SwiftUI

/// The global mini-player. With nothing loaded it says so plainly;
/// with a track loaded it is a real control: play/pause works here,
/// and tapping the bar opens Now Playing.
public struct MiniPlayerBar: View {
    @EnvironmentObject private var engine: PlaybackEngine
    @State private var showNowPlaying = false

    public init() {}

    public var body: some View {
        Group {
            if let track = engine.currentTrack {
                loadedBar(track)
            } else if let error = engine.playbackError {
                errorBar(error)
            } else {
                idleBar
            }
        }
        .sheet(isPresented: $showNowPlaying) {
            NowPlayingView()
        }
        .onChange(of: engine.currentTrack?.id) { _, newID in
            #if DEBUG
            if newID != nil, ProcessInfo.processInfo.arguments.contains("-nowplaying") {
                showNowPlaying = true
            }
            #endif
        }
    }

    private var idleBar: some View {
        HStack(spacing: 12) {
            ArtworkView(size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text("Nothing playing")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppColors.ink)
                Text("Tap a song on Home or Search")
                    .font(.caption)
                    .foregroundStyle(AppColors.inkSecondary)
            }

            private func errorBar(_ message: String) -> some View {
                HStack(spacing: 12) {
                    Image(systemName: "exclamationmark.triangle")
                        .foregroundStyle(AppColors.accent)
                    Text(message)
                        .font(.caption)
                        .foregroundStyle(AppColors.inkSecondary)
                        .lineLimit(2)
                    Spacer()
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .glassSurface(cornerRadius: 16)
                .padding(.horizontal, 12)
                .padding(.bottom, 4)
            }
            Spacer()
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .glassSurface(cornerRadius: 16)
        .padding(.horizontal, 12)
        .padding(.bottom, 4)
    }

    private func loadedBar(_ track: Track) -> some View {
        HStack(spacing: 12) {
            ArtworkView(url: track.artworkURL, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppColors.ink)
                    .lineLimit(1)
                Text(track.artistName)
                    .font(.caption)
                    .foregroundStyle(AppColors.inkSecondary)
                    .lineLimit(1)
            }
            Spacer()
            Button {
                showNowPlaying = true
            } label: {
                Image(systemName: "chevron.up")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppColors.inkSecondary)
                    .accessibilityLabel("Open Now Playing")
            }
            .buttonStyle(.plain)
            Button {
                engine.togglePlayPause()
            } label: {
                Image(systemName: engine.isPlaying ? "pause.fill" : "play.fill")
                    .font(.title3)
                    .foregroundStyle(AppColors.ink)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .glassSurface(cornerRadius: 16)
        .padding(.horizontal, 12)
        .padding(.bottom, 4)
        .contentShape(Rectangle())
    }
}
