import Foundation

/// Merged search over the catalogs. Audius results are playable;
/// MusicBrainz results that are not already covered by a playable hit
/// are honestly labeled metadata only. A provider failing never
/// silently empties the other provider's results.
@MainActor
public final class SearchService: ObservableObject {
    @Published public private(set) var playable: [Track] = []
    @Published public private(set) var metadataOnly: [Track] = []
    @Published public private(set) var isSearching = false
    @Published public private(set) var hasSearched = false
    @Published public private(set) var errorMessage: String?

    private let audius = AudiusProvider()
    private let musicBrainz = MusicBrainzProvider()

    public init() {}

    public func search(_ query: String) async {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        isSearching = true
        hasSearched = true
        errorMessage = nil

        do {
            playable = try await audius.searchTracks(query: trimmed)
        } catch {
            playable = []
            errorMessage = "Search failed. The catalogs may be unreachable right now."
        }

        if let mbResults = try? await musicBrainz.searchTracks(query: trimmed) {
            let playableKeys = Set(playable.map { Self.key($0) })
            metadataOnly = mbResults.filter { !playableKeys.contains(Self.key($0)) }
        } else {
            metadataOnly = []
        }
        isSearching = false
    }

    private static func key(_ track: Track) -> String {
        (track.title + "|" + track.artistName).lowercased()
    }
}
