import Foundation

/// Single construction point for app services (SPEC architecture).
/// Provider adapters register here as their phases land; in Milestone 1
/// the arrays are honestly empty and Search says so.
public struct DependencyContainer {
    public var metadataProviders: [any MusicMetadataProvider]
    public var audioProviders: [any AudioSourceProvider]
    public var lyricsProviders: [any LyricsProvider]

    public static let live = DependencyContainer(
        metadataProviders: [],
        audioProviders: [],
        lyricsProviders: []
    )
}
