import Foundation

/// MusicBrainz recording payload (subset of the real search response).
/// Mapping into the canonical Track lives here so the rest of the app
/// never touches MusicBrainz shapes. Metadata only: this provider can
/// never make a track playable by itself.
public struct MusicBrainzRecording: Decodable, Sendable {
    public let id: String
    public let title: String
    public let length: Int?                 // milliseconds
    public let artistCredit: [ArtistCredit]?
    public let releases: [Release]?

    public struct ArtistCredit: Decodable, Sendable {
        public let name: String?
        public let artist: ArtistRef?
        public struct ArtistRef: Decodable, Sendable { public let name: String }
    }
    public struct Release: Decodable, Sendable { public let title: String? }

    enum CodingKeys: String, CodingKey {
        case id, title, length
        case artistCredit = "artist-credit"
        case releases
    }

    public func toTrack() -> Track {
        let artist = artistCredit?.first?.artist?.name
            ?? artistCredit?.first?.name
            ?? "Unknown Artist"
        return Track(
            id: "musicbrainz:\(id)",
            title: title,
            artistName: artist,
            albumTitle: releases?.first?.title,
            durationSeconds: length.map { Double($0) / 1000.0 },
            providerID: "musicbrainz"
        )
    }
}
