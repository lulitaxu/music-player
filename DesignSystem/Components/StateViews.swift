import SwiftUI

/// Shared loading / empty / error states (SPEC Phase 1 requirement).
/// Screens use these instead of inventing their own, and an empty state
/// always says what will land there and in which phase. No fake content.
public struct EmptyStateView: View {
    public let systemImage: String
    public let title: String
    public let message: String
    public init(systemImage: String, title: String, message: String) {
        self.systemImage = systemImage; self.title = title; self.message = message
    }
    public var body: some View {
        ContentUnavailableView(title, systemImage: systemImage, description: Text(message))
    }
}

public struct ErrorStateView: View {
    public let message: String
    public var onRetry: (() -> Void)? = nil
    public init(message: String, onRetry: (() -> Void)? = nil) {
        self.message = message; self.onRetry = onRetry
    }
    public var body: some View {
        ContentUnavailableView {
            Label("Something went wrong", systemImage: "exclamationmark.triangle")
        } description: {
            Text(message)
        } actions: {
            if let onRetry { Button("Try again", action: onRetry) }
        }
    }
}

public struct LoadingView: View {
    public let label: String
    public init(label: String = "Loading") { self.label = label }
    public var body: some View {
        ProgressView(label)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
