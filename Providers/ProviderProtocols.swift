import Foundation

/// What a provider is allowed to claim. Adapters declare capabilities up
/// front so the UI never promises a stream or download the provider
/// cannot legally/technically deliver.
public struct ProviderCapabilities: Sendable {
    public var canSearchTracks: Bool = false
    public var canSearchArtists: Bool = false
    public var canSearchAlbums: Bool = false
    public var canStream: Bool = false
    public var canDownload: Bool = false
    public var providesSyncedLyrics: Bool = false
    public var providesPlainLyrics: Bool = false
    public var requiresAccount: Bool = false
    public init() {}
}

public protocol MusicMetadataProvider: Sendable {
    var id: String { get }                       // "musicbrainz", "audius", ...
    var capabilities: ProviderCapabilities { get }
    func searchTracks(query: String) async throws -> [Track]
    func searchArtists(query: String) async throws -> [Artist]
    func searchAlbums(query: String) async throws -> [Album]
}

public protocol AudioSourceProvider: Sendable {
    var id: String { get }
    var capabilities: ProviderCapabilities { get }
    func availability(for track: Track) async throws -> AudioAvailability
    func stream(for track: Track) async throws -> AudioStream?
    func download(for track: Track) async throws -> DownloadSource?
}

public struct LyricsResult: Sendable {
    public let plainText: String?
    public let lrcText: String?          // raw LRC when synced lyrics exist
    public let languageCode: String?
    public let isTranslation: Bool
    public init(plainText: String?, lrcText: String?,
                languageCode: String? = nil, isTranslation: Bool = false) {
        self.plainText = plainText; self.lrcText = lrcText
        self.languageCode = languageCode; self.isTranslation = isTranslation
    }
}

public protocol LyricsProvider: Sendable {
    var id: String { get }
    var capabilities: ProviderCapabilities { get }
    /// Matching must use title + artist + album + duration (and ISRC when
    /// available). Title-only matching is a bug, not a shortcut.
    func searchLyrics(for track: Track) async throws -> [LyricsResult]
}
