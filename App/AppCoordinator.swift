import SwiftUI

public enum AppTab: Int, CaseIterable {
    case home, search, library, downloads, settings

    public var title: String {
        switch self {
        case .home: return "Home"
        case .search: return "Search"
        case .library: return "Library"
        case .downloads: return "Downloads"
        case .settings: return "Settings"
        }
    }

    public var systemImage: String {
        switch self {
        case .home: return "house"
        case .search: return "magnifyingglass"
        case .library: return "music.note.list"
        case .downloads: return "arrow.down.circle"
        case .settings: return "gearshape"
        }
    }
}

@MainActor
public final class AppCoordinator: ObservableObject {
    @Published public var selectedTab: AppTab = .home
    public init() {}
}
