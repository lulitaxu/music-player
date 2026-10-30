import SwiftUI

/// The five-tab shell (SPEC navigation). The mini-player docks above the
/// tab bar via a bottom safe-area inset on the TabView.
public struct RootTabView: View {
    public let container: DependencyContainer
    @EnvironmentObject private var coordinator: AppCoordinator
    @EnvironmentObject private var engine: PlaybackEngine
    @State private var didApplyLaunchArgs = false

    public init(container: DependencyContainer) { self.container = container }

    public var body: some View {
        TabView(selection: $coordinator.selectedTab) {
            HomeView()
                .tabItem { Label(AppTab.home.title, systemImage: AppTab.home.systemImage) }
                .tag(AppTab.home)
            SearchView()
                .tabItem { Label(AppTab.search.title, systemImage: AppTab.search.systemImage) }
                .tag(AppTab.search)
            LibraryView()
                .tabItem { Label(AppTab.library.title, systemImage: AppTab.library.systemImage) }
                .tag(AppTab.library)
            DownloadsView()
                .tabItem { Label(AppTab.downloads.title, systemImage: AppTab.downloads.systemImage) }
                .tag(AppTab.downloads)
            SettingsView()
                .tabItem { Label(AppTab.settings.title, systemImage: AppTab.settings.systemImage) }
                .tag(AppTab.settings)
        }
        .tint(AppColors.accent)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            MiniPlayerBar()
        }
        .onAppear { applyLaunchArgs() }
    }

    /// CI screenshot hooks (Debug builds only): -tab <name> selects a
    /// tab, -demoplay starts real trending playback, -nowplaying opens
    /// the Now Playing sheet once a track loads. Release builds ignore
    /// all of it.
    private func applyLaunchArgs() {
        guard !didApplyLaunchArgs else { return }
        didApplyLaunchArgs = true
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-tab"), args.indices.contains(i + 1) {
            switch args[i + 1] {
            case "search": coordinator.selectedTab = .search
            case "library": coordinator.selectedTab = .library
            case "downloads": coordinator.selectedTab = .downloads
            case "settings": coordinator.selectedTab = .settings
            default: break
            }
        }
        if args.contains("-demoplay") {
            Task {
                let provider = AudiusProvider()
                if let tracks = try? await provider.trending(limit: 10), !tracks.isEmpty {
                    engine.playQueue(tracks, startAt: 0)
                }
            }
        }
        #endif
    }
}
