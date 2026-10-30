import SwiftUI

@main
struct MusicApp: App {
    @StateObject private var coordinator = AppCoordinator()
    @StateObject private var playback = PlaybackEngine(audioProvider: AudiusProvider())
    private let container = DependencyContainer.live

    var body: some Scene {
        WindowGroup {
            RootTabView(container: container)
                .environmentObject(coordinator)
                .environmentObject(playback)
                .preferredColorScheme(.dark)
        }
    }
}
