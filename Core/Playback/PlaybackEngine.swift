import AVFoundation
import Combine
import MediaPlayer
import UIKit

/// The playback engine: one AVPlayer, one queue, Lock Screen and
/// remote-command integration, background audio session. It resolves
/// streams through whichever AudioSourceProvider it was built with and
/// knows no provider names or URLs itself.
///
/// Threading: all entry points are called from the main thread (views,
/// main-queue observers, the remote command center). Published state is
/// only mutated there or via MainActor hops.
@MainActor
public final class PlaybackEngine: ObservableObject {
    @Published public private(set) var currentTrack: Track?
    @Published public private(set) var isPlaying = false
    @Published public private(set) var queue: [Track] = []
    @Published public private(set) var currentIndex = 0
    @Published public private(set) var currentTime: Double = 0
    @Published public private(set) var duration: Double = 0
    @Published public private(set) var playbackError: String?

    private let player = AVPlayer()
    private let audioProvider: any AudioSourceProvider
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var loadToken = UUID()

    public init(audioProvider: any AudioSourceProvider) {
        self.audioProvider = audioProvider
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.5, preferredTimescale: 600),
            queue: .main
        ) { [weak self] time in
            guard let self else { return }
            self.currentTime = time.seconds.isFinite ? time.seconds : 0
            if let d = self.player.currentItem?.duration.seconds, d.isFinite, d > 0 {
                self.duration = d
            }
            self.updateNowPlaying()
        }
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime, object: nil, queue: .main
        ) { [weak self] note in
            guard let self,
                  let item = note.object as? AVPlayerItem,
                  item == self.player.currentItem else { return }
            self.advance()
        }
        setupRemoteCommands()
    }

    deinit {
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
    }

    // MARK: Public controls

    /// Starts a queue of playable tracks at the tapped position.
    public func playQueue(_ tracks: [Track], startAt index: Int) {
        guard tracks.indices.contains(index) else { return }
        playbackError = nil
        let tappedID = tracks[index].id
        queue = tracks
        currentIndex = queue.firstIndex(where: { $0.id == tappedID }) ?? 0
        loadCurrent()
    }

    public func playAt(index: Int) {
        guard queue.indices.contains(index) else { return }
        playbackError = nil
        currentIndex = index
        loadCurrent()
    }

    public func togglePlayPause() {
        guard currentTrack != nil else { return }
        if isPlaying {
            player.pause()
            isPlaying = false
        } else {
            activateSession()
            player.play()
            isPlaying = true
        }
        updateNowPlaying()
    }

    public func next() { advance() }

    public func previous() {
        if currentTime > 3 {
            seek(to: 0)
        } else if currentIndex > 0 {
            currentIndex -= 1
            loadCurrent()
        } else {
            seek(to: 0)
        }
    }

    public func seek(to seconds: Double) {
        let targetSeconds = min(max(0, seconds), duration > 0 ? duration : max(0, seconds))
        let target = CMTime(seconds: targetSeconds, preferredTimescale: 600)
        player.seek(to: target)
        currentTime = targetSeconds
        updateNowPlaying()
    }

    // MARK: Internals

    private func advance() {
        if currentIndex + 1 < queue.count {
            currentIndex += 1
            loadCurrent()
        } else {
            isPlaying = false
            updateNowPlaying()
        }
    }

    private func loadCurrent() {
        guard queue.indices.contains(currentIndex) else { return }
        loadToken = UUID()
        let token = loadToken
        let track = queue[currentIndex]
        currentTrack = track
        playbackError = nil
        currentTime = 0
        duration = track.durationSeconds ?? 0
        activateSession()
        let provider = audioProvider
        Task { [weak self] in
            guard let self else { return }
            do {
                guard let stream = try await provider.stream(for: track) else {
                    throw ProviderError.transport
                }
                guard token == self.loadToken, self.currentTrack?.id == track.id else { return }
                self.player.replaceCurrentItem(with: AVPlayerItem(url: stream.url))
                self.player.play()
                self.isPlaying = true
                self.updateNowPlaying()
                self.loadArtwork(for: track)
            } catch {
                guard token == self.loadToken, self.currentTrack?.id == track.id else { return }
                self.playbackError = "This track could not be played."
                self.player.replaceCurrentItem(with: nil)
                self.isPlaying = false
                if self.currentIndex + 1 < self.queue.count {
                    self.currentIndex += 1
                    self.loadCurrent()
                } else {
                    self.currentTrack = nil
                    self.duration = 0
                    self.updateNowPlaying()
                }
            }
        }
    }

    private func activateSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default)
        try? session.setActive(true)
    }

    private func setupRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in
            guard let self, !self.isPlaying else { return .commandFailed }
            self.togglePlayPause()
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            guard let self, self.isPlaying else { return .commandFailed }
            self.togglePlayPause()
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            self?.togglePlayPause()
            return .success
        }
        center.nextTrackCommand.addTarget { [weak self] _ in
            self?.next()
            return .success
        }
        center.previousTrackCommand.addTarget { [weak self] _ in
            self?.previous()
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let self,
                  let positionEvent = event as? MPChangePlaybackPositionCommandEvent
            else { return .commandFailed }
            self.seek(to: positionEvent.positionTime)
            return .success
        }
    }

    private func updateNowPlaying() {
        guard let track = currentTrack else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyArtist: track.artistName,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: currentTime,
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
        ]
        if let album = track.albumTitle {
            info[MPMediaItemPropertyAlbumTitle] = album
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func loadArtwork(for track: Track) {
        guard let url = track.artworkURL else { return }
        Task { [weak self] in
            guard let (data, _) = try? await URLSession.shared.data(from: url),
                  let image = UIImage(data: data) else { return }
            await MainActor.run { [weak self] in
                guard let self, self.currentTrack?.id == track.id else { return }
                var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                MPNowPlayingInfoCenter.default().nowPlayingInfo = info
            }
        }
    }
}
