import SwiftUI

/// Settings in Milestone 1 is informational only: real facts about the
/// build and its providers. No decorative toggles that do nothing;
/// functional preferences arrive with their features in Phase 6.
public struct SettingsView: View {
    public init() {}
    public var body: some View {
        NavigationStack {
            List {
                Section("About") {
                    LabeledContent("Version", value: "0.2.0 (search + playback)")
                    Text("Streams via Audius. Metadata via MusicBrainz and the Cover Art Archive.")
                        .font(AppTypography.footnote)
                        .foregroundStyle(AppColors.inkSecondary)
                    LabeledContent("Target device", value: "iPhone XS")
                    LabeledContent("Minimum OS", value: "iOS 18")
                }
                Section("Providers") {
                    LabeledContent("Metadata", value: "MusicBrainz")
                    LabeledContent("Artwork", value: "Cover Art Archive")
                    LabeledContent("Streaming", value: "Audius")
                    LabeledContent("Lyrics", value: "LRCLIB")
                }
                Section("Principles") {
                    Text("No DRM bypass, ever. A song being listed is not the same as it being playable, and this app always labels which one is true.")
                        .font(.footnote)
                        .foregroundStyle(AppColors.inkSecondary)
                }
            }
            .navigationTitle("Settings")
        }
    }
}
