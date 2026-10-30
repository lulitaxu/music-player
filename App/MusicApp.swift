import SwiftUI

@main
struct MusicApp: App {
    @StateObject private var coordinator = AppCoordinator()
    private let container = DependencyContainer.live

    var body: some Scene {
        WindowGroup {
            RootTabView(container: container)
                .environmentObject(coordinator)
                .preferredColorScheme(.dark)
        }
    }
}
