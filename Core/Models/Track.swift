import Foundation

/// Canonical track model. Every provider maps its own payloads into these
/// types; the rest of the app never sees provider-specific shapes.
public struct Track: Identifiable, Codable, Equatable, Sendable {
    public let id: String              // provider-scoped stable id, e.g. "audius:12345"
    public var title: String
    public var artistName: String
    public var albumTitle: String?
    public var durationSeconds: Double?
    public var isrc: String?
    public var artworkURL: URL?
    public var providerID: String      // which metadata provider produced this record

    public init(id: String, title: String, artistName: String, albumTitle: String? = nil,
                durationSeconds: Double? = nil, isrc: String? = nil,
                artworkURL: URL? = nil, providerID: String) {
        self.id = id
        self.title = title
        self.artistName = artistName
        self.albumTitle = albumTitle
        self.durationSeconds = durationSeconds
        self.isrc = isrc
        self.artworkURL = artworkURL
        self.providerID = providerID
    }
}

public struct Artist: Identifiable, Codable, Equatable, Sendable {
    public let id: String
    public var name: String
    public var artworkURL: URL?
    public init(id: String, name: String, artworkURL: URL? = nil) {
        self.id = id; self.name = name; self.artworkURL = artworkURL
    }
}

public struct Album: Identifiable, Codable, Equatable, Sendable {
    public let id: String
    public var title: String
    public var artistName: String
    public var artworkURL: URL?
    public var releaseYear: Int?
    public init(id: String, title: String, artistName: String,
                artworkURL: URL? = nil, releaseYear: Int? = nil) {
        self.id = id; self.title = title; self.artistName = artistName
        self.artworkURL = artworkURL; self.releaseYear = releaseYear
    }
}

/// The four honest states every search result must declare.
/// The UI shows this verbatim; it is never inferred from a metadata hit.
public enum AudioAvailability: String, Codable, Sendable {
    case playable        // a supported source can stream it now
    case downloadable    // authorized offline download exists
    case metadataOnly    // we know the song exists; no playable source
    case local           // audio file is on the device
}

public struct AudioStream: Sendable {
    public let url: URL
    public let qualityLabel: String?   // e.g. "320 kbps", "FLAC"
    public let expiresAt: Date?        // signed URLs expire; nil means stable
    public init(url: URL, qualityLabel: String? = nil, expiresAt: Date? = nil) {
        self.url = url; self.qualityLabel = qualityLabel; self.expiresAt = expiresAt
    }
}

public struct DownloadSource: Sendable {
    public let url: URL
    public let fileExtension: String   // "mp3", "m4a", "flac"
    public init(url: URL, fileExtension: String) {
        self.url = url; self.fileExtension = fileExtension
    }
}
