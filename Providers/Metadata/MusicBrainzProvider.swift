import Foundation

/// MusicBrainz adapter. Metadata only: this provider can describe the
/// world's music but can never make a track playable, and its results
/// are labeled metadataOnly wherever they surface.
/// Etiquette (their rules): max 1 request/second, identifying
/// User-Agent. Search is user-initiated, so the rate limit is natural.
public struct MusicBrainzProvider: MusicMetadataProvider {
    public let id = "musicbrainz"
    public var capabilities: ProviderCapabilities {
        var c = ProviderCapabilities()
        c.canSearchTracks = true
        return c
    }

    private let client = APIClient()
    private let headers = [
        "User-Agent": "MusicPlayer/0.2 ( contact@arafatarzu.me )",
        "Accept": "application/json",
    ]

    public init() {}

    public func searchTracks(query: String) async throws -> [Track] {
        var components = URLComponents(string: "https://musicbrainz.org/ws/2/recording/")
        components?.queryItems = [
            URLQueryItem(name: "query", value: query),
            URLQueryItem(name: "fmt", value: "json"),
            URLQueryItem(name: "limit", value: "25"),
        ]
        guard let url = components?.url else { throw ProviderError.transport }
        let response = try await client.get(MusicBrainzSearchResponse.self, url: url, headers: headers)
        return response.recordings.map { $0.toTrack() }
    }

    public func searchArtists(query: String) async throws -> [Artist] { throw ProviderError.unsupported }
    public func searchAlbums(query: String) async throws -> [Album] { throw ProviderError.unsupported }
}
