import SwiftUI

public struct SearchView: View {
    public init() {}
    public var body: some View {
        NavigationStack {
            EmptyStateView(
                systemImage: "magnifyingglass",
                title: "Search every catalog",
                message: "MusicBrainz search lands in Phase 2. Results will always say whether a song is playable, downloadable, or metadata only."
            )
            .navigationTitle("Search")
        }
    }
}
