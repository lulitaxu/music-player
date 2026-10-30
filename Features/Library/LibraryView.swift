import SwiftUI

public struct LibraryView: View {
    public init() {}
    public var body: some View {
        NavigationStack {
            EmptyStateView(
                systemImage: "music.note.list",
                title: "Your library is empty",
                message: "Imported files appear in Phase 2 indexing; favorites and playlists arrive in Phase 6."
            )
            .navigationTitle("Library")
        }
    }
}
