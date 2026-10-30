import SwiftUI

@main
struct MusicApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

/// Placeholder root for Phase 0. Replaced by the tab shell in Phase 1
/// (Milestone 1 acceptance is a building, launching empty shell, not UI).
struct ContentView: View {
    var body: some View {
        Text("Music Player")
    }
}
