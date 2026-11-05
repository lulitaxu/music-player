import Foundation

/// Audius adapter. Audius is the primary authorized audio source:
/// artists upload their own music and the public API returns metadata
/// plus a stream endpoint per track. No account, no key; the API asks
/// callers to identify their app via the app_name parameter.
public struct AudiusProvider: MusicMetadataProvider, AudioSourceProvider {
    public let id = "audius"
    public var capabilities: ProviderCapabilities {
        var c = ProviderCapabilities()
        c.canSearchTracks = true
        c.canStream = true
        c.canDownload = false   // per-track artist setting; wired in the downloads phase
        return c
    }

    private let client = APIClient()
    private static let base = "https://api.audius.co/v1"
    private static let appName = "arzxsh"

    public init() {}

    // MARK: URLs

    public static func streamURL(forTrackID rawID: String) -> URL? {
        var components = URLComponents(string: "\(base)/tracks/\(rawID)/stream")
        components?.queryItems = [URLQueryItem(name: "app_name", value: appName)]
        return components?.url
    }

    private func url(_ path: String, queryItems: [URLQueryItem]) -> URL? {
        var components = URLComponents(string: Self.base + path)
        components?.queryItems = queryItems + [URLQueryItem(name: "app_name", value: Self.appName)]
        return components?.url
    }

    // MARK: MusicMetadataProvider

    public func searchTracks(query: String) async throws -> [Track] {
        guard let url = url("/tracks/search", queryItems: [
            URLQueryItem(name: "query", value: query),
            URLQueryItem(name: "limit", value: "25"),
        ]) else { throw ProviderError.transport }
        let response = try await client.get(AudiusListResponse.self, url: url)
        return response.data.map { $0.toTrack() }
    }

    public func trending(limit: Int = 25) async throws -> [Track] {
        guard let url = url("/tracks/trending", queryItems: [
            URLQueryItem(name: "limit", value: "\(limit)"),
        ]) else { throw ProviderError.transport }
        let response = try await client.get(AudiusListResponse.self, url: url)
        return response.data.map { $0.toTrack() }
    }

    public func searchArtists(query: String) async throws -> [Artist] { throw ProviderError.unsupported }
    public func searchAlbums(query: String) async throws -> [Album] { throw ProviderError.unsupported }

    // MARK: AudioSourceProvider

    public func availability(for track: Track) async throws -> AudioAvailability {
        track.providerID == id ? .playable : .metadataOnly
    }

    public func stream(for track: Track) async throws -> AudioStream? {
        guard track.providerID == id, track.id.hasPrefix("\(id):") else { return nil }
        let rawID = String(track.id.dropFirst("\(id):".count))
        guard let url = Self.streamURL(forTrackID: rawID) else { return nil }
        return AudioStream(url: url)
    }

    public func download(for track: Track) async throws -> DownloadSource? { nil }
}
