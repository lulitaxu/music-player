import SwiftUI

@MainActor
final class HomeViewModel: ObservableObject {
    @Published var trending: [Track] = []
    @Published var isLoading = false
    @Published var errorMessage: String?
    private let provider = AudiusProvider()

    func load() async {
        guard trending.isEmpty, !isLoading else { return }
        isLoading = true
        errorMessage = nil
        do {
            trending = try await provider.trending()
        } catch {
            errorMessage = "Could not load trending. Check your connection and try again."
        }
        isLoading = false
    }
}

public struct HomeView: View {
    @StateObject private var model = HomeViewModel()
    @EnvironmentObject private var engine: PlaybackEngine

    public init() {}

    public var body: some View {
        NavigationStack {
            content
                .navigationTitle("arzxsh")
                .task { await model.load() }
        }
    }

    @ViewBuilder
    private var content: some View {
        if model.isLoading {
            LoadingView(label: "Loading trending")
        } else if let error = model.errorMessage {
            ErrorStateView(message: error) {
                Task { await model.load() }
            }
        } else {
            List {
                Section("Trending") {
                    ForEach(Array(model.trending.enumerated()), id: \.element.id) { index, track in
                        Button {
                            engine.playQueue(model.trending, startAt: index)
                        } label: {
                            TrackRow(track: track, availability: .playable,
                                     isCurrent: engine.currentTrack?.id == track.id)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .listStyle(.plain)
        }
    }
}
