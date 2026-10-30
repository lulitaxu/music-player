import SwiftUI

public struct HomeView: View {
    public init() {}
    public var body: some View {
        NavigationStack {
            EmptyStateView(
                systemImage: "clock.arrow.circlepath",
                title: "Nothing played yet",
                message: "Recently played and discovery sections fill in once the playback engine lands in Phase 3."
            )
            .navigationTitle("Home")
        }
    }
}
