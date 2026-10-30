import XCTest
@testable import MusicApp

/// Layer 2 provider tests: real response envelopes decode, the Audius
/// stream URL is built from a track id, and unimplemented provider
/// surface fails loudly (ProviderError.unsupported) instead of faking
/// results. All offline, fixtures are recorded response shapes.
final class ProviderTests: XCTestCase {

    func testAudiusStreamURL() {
        let url = AudiusProvider.streamURL(forTrackID: "eYRWn")
        XCTAssertNotNil(url)
        XCTAssertTrue(url!.absoluteString.contains("/v1/tracks/eYRWn/stream"))
        XCTAssertTrue(url!.absoluteString.contains("app_name="))
    }

    func testAudiusListResponseDecodes() throws {
        let json = """
        { "data": [ {
            "id": "eYRWn", "title": "Golden Hour", "duration": 187,
            "artwork": { "1000x1000": "https://example.org/a.jpg" },
            "user": { "name": "Indie Artist" }
        } ] }
        """
        let response = try JSONDecoder().decode(AudiusListResponse.self, from: Data(json.utf8))
        XCTAssertEqual(response.data.count, 1)
        XCTAssertEqual(response.data[0].toTrack().providerID, "audius")
    }

    func testMusicBrainzSearchResponseDecodes() throws {
        let json = """
        { "recordings": [ {
            "id": "abc", "title": "Midnight Run", "length": 214000,
            "artist-credit": [ { "artist": { "name": "The Example Band" } } ],
            "releases": [ { "title": "First Light" } ]
        } ] }
        """
        let response = try JSONDecoder().decode(MusicBrainzSearchResponse.self, from: Data(json.utf8))
        XCTAssertEqual(response.recordings.count, 1)
        XCTAssertEqual(response.recordings[0].toTrack().albumTitle, "First Light")
    }

    func testUnsupportedSurfacesThrow() async {
        let audius = AudiusProvider()
        do {
            _ = try await audius.searchArtists(query: "x")
            XCTFail("searchArtists must throw ProviderError.unsupported")
        } catch let error as ProviderError {
            XCTAssertEqual(error, .unsupported)
        } catch {
            XCTFail("wrong error type: \(error)")
        }
    }
}
