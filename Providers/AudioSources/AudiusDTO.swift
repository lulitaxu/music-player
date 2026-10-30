import Foundation

/// Audius track payload (subset of the real API response). Audius is
/// the primary authorized audio source: artists upload their own music
/// and the API returns real stream URLs (Phase 3 wires the stream
/// endpoint; this DTO is the track record).
public struct AudiusTrack: Decodable, Sendable {
    public let id: String
    public let title: String
    public let duration: Int                // seconds
    public let artwork: Artwork?
    public let user: User

    public struct Artwork: Decodable, Sendable {
        public let url1000: String?
        enum CodingKeys: String, CodingKey { case url1000 = "1000x1000" }
    }
    public struct User: Decodable, Sendable { public let name: String }

    public func toTrack() -> Track {
        Track(
            id: "audius:\(id)",
            title: title,
            artistName: user.name,
            durationSeconds: Double(duration),
            artworkURL: artwork?.url1000.flatMap { URL(string: $0) },
            providerID: "audius"
        )
    }
}
