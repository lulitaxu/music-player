import Foundation

/// Single construction point for app services (SPEC architecture).
/// Adapters register here as their phases land. Lyrics providers join
/// in the lyrics layer; downloads in the offline layer.
public struct DependencyContainer {
    public var metadataProviders: [any MusicMetadataProvider]
    public var audioProviders: [any AudioSourceProvider]
    public var lyricsProviders: [any LyricsProvider]

    public static let live = DependencyContainer(
        metadataProviders: [AudiusProvider(), MusicBrainzProvider()],
        audioProviders: [AudiusProvider()],
        lyricsProviders: []
    )
}
