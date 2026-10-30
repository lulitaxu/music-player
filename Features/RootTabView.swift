import SwiftUI

/// The five-tab shell (SPEC navigation). The mini-player docks above the
/// tab bar via a bottom safe-area inset on the TabView.
public struct RootTabView: View {
    public let container: DependencyContainer
    @EnvironmentObject private var coordinator: AppCoordinator

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
    }
}
