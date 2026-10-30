import XCTest
@testable import MusicApp

/// Milestone 1 acceptance tests (docs/PHASE0.md section 7):
/// availability states round-trip, and both provider payload shapes
/// decode into canonical Tracks. Fixtures are recorded response shapes
/// embedded as literals: tests never touch the network.
final class ModelTests: XCTestCase {

    func testAvailabilityRoundTrips() throws {
        for state in [AudioAvailability.playable, .downloadable, .metadataOnly, .local] {
            let data = try JSONEncoder().encode(state)
            XCTAssertEqual(try JSONDecoder().decode(AudioAvailability.self, from: data), state)
        }
    }

    func testMusicBrainzRecordingDecodes() throws {
        let json = """
        {
          "id": "8f3472e2-0000-4000-8000-000000000000",
          "title": "Midnight Run",
          "length": 214000,
          "artist-credit": [
            { "name": "The Example Band", "artist": { "name": "The Example Band" } }
          ],
          "releases": [ { "title": "First Light" } ]
        }
        """
        let recording = try JSONDecoder().decode(MusicBrainzRecording.self, from: Data(json.utf8))
        let track = recording.toTrack()
        XCTAssertEqual(track.id, "musicbrainz:8f3472e2-0000-4000-8000-000000000000")
        XCTAssertEqual(track.title, "Midnight Run")
        XCTAssertEqual(track.artistName, "The Example Band")
        XCTAssertEqual(track.albumTitle, "First Light")
        XCTAssertEqual(track.durationSeconds, 214.0)
        XCTAssertEqual(track.providerID, "musicbrainz")
    }

    func testAudiusTrackDecodes() throws {
        let json = """
        {
          "id": "eYRWn",
          "title": "Golden Hour",
          "duration": 187,
          "artwork": { "1000x1000": "https://example.org/art/1000x1000.jpg" },
          "user": { "name": "Indie Artist" }
        }
        """
        let audiusTrack = try JSONDecoder().decode(AudiusTrack.self, from: Data(json.utf8))
        let track = audiusTrack.toTrack()
        XCTAssertEqual(track.id, "audius:eYRWn")
        XCTAssertEqual(track.title, "Golden Hour")
        XCTAssertEqual(track.artistName, "Indie Artist")
        XCTAssertEqual(track.durationSeconds, 187.0)
        XCTAssertEqual(track.artworkURL?.absoluteString, "https://example.org/art/1000x1000.jpg")
        XCTAssertEqual(track.providerID, "audius")
    }
}
