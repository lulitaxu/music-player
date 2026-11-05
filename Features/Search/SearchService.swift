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
    @Published public private(set) var warningMessage: String?

    private let audius = AudiusProvider()
    private let musicBrainz = MusicBrainzProvider()
    private var requestID = UUID()

    public init() {}

    public func search(_ query: String) async {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let requestID = UUID()
        self.requestID = requestID
        isSearching = true
        hasSearched = true
        errorMessage = nil
        warningMessage = nil
        playable = []
        metadataOnly = []

        do {
            let results = try await audius.searchTracks(query: trimmed)
            guard self.requestID == requestID else { return }
            playable = results
        } catch {
            guard self.requestID == requestID else { return }
            playable = []
            warningMessage = "Playable results are unavailable right now. Showing metadata results if available."
        }

        if let mbResults = try? await musicBrainz.searchTracks(query: trimmed) {
            guard self.requestID == requestID else { return }
            let playableKeys = Set(playable.map { Self.key($0) })
            metadataOnly = mbResults.filter { !playableKeys.contains(Self.key($0)) }
        } else {
            guard self.requestID == requestID else { return }
            metadataOnly = []
        }
        if playable.isEmpty && metadataOnly.isEmpty && warningMessage != nil {
            errorMessage = "The catalogs are unreachable right now."
        }
        isSearching = false
    }

    private static func key(_ track: Track) -> String {
        (track.title + "|" + track.artistName).lowercased()
    }
}
