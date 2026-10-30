import SwiftUI

public struct SearchView: View {
    @StateObject private var service = SearchService()
    @EnvironmentObject private var engine: PlaybackEngine
    @State private var query = ""

    public init() {}

    public var body: some View {
        NavigationStack {
            content
                .navigationTitle("Search")
                .searchable(text: $query, prompt: Text("Songs, artists, albums"))
                .onSubmit(of: .search) {
                    Task { await service.search(query) }
                }
        }
    }

    @ViewBuilder
    private var content: some View {
        if service.isSearching {
            LoadingView(label: "Searching the catalogs")
        } else if let error = service.errorMessage, service.playable.isEmpty && service.metadataOnly.isEmpty {
            ErrorStateView(message: error) {
                Task { await service.search(query) }
            }
        } else if !service.hasSearched {
            EmptyStateView(
                systemImage: "magnifyingglass",
                title: "Search every catalog",
                message: "Playable results stream from Audius. Everything else is labeled metadata only, never dressed up as playable."
            )
        } else if service.playable.isEmpty && service.metadataOnly.isEmpty {
            EmptyStateView(
                systemImage: "questionmark.circle",
                title: "No results",
                message: "Nothing matched in the catalogs. Try a different spelling or another artist."
            )
        } else {
            List {
                if !service.playable.isEmpty {
                    Section("Playable") {
                        ForEach(Array(service.playable.enumerated()), id: \.element.id) { index, track in
                            Button {
                                engine.playQueue(service.playable, startAt: index)
                            } label: {
                                TrackRow(track: track, availability: .playable,
                                         isCurrent: engine.currentTrack?.id == track.id)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                if !service.metadataOnly.isEmpty {
                    Section("Metadata only") {
                        ForEach(service.metadataOnly) { track in
                            TrackRow(track: track, availability: .metadataOnly)
                        }
                    }
                }
            }
            .listStyle(.plain)
        }
    }
}
