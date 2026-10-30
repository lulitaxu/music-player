import SwiftUI

public struct DownloadsView: View {
    public init() {}
    public var body: some View {
        NavigationStack {
            EmptyStateView(
                systemImage: "arrow.down.circle",
                title: "No downloads yet",
                message: "Authorized downloads and the offline library arrive in Phase 5. Only providers that permit a download will ever offer one."
            )
            .navigationTitle("Downloads")
        }
    }
}
