import XCTest
@testable import MusicApp

/// International text acceptance tests (SPEC addendum item 5).
final class LocalizedTextTests: XCTestCase {

    func testBanglaSurvivesNormalization() {
        let line = "আমার সোনার বাংলা, আমি তোমায় ভালোবাসি"
        XCTAssertEqual(LocalizedText.normalize(line), line)
        XCTAssertFalse(LocalizedText.normalize(line).contains("\u{FFFD}"))
    }

    func testDecomposedSequenceComposes() {
        // "e" + combining acute must become the single composed "é".
        let normalized = LocalizedText.normalize("e\u{0301}")
        XCTAssertEqual(normalized, "\u{00E9}")
        XCTAssertEqual(normalized.count, 1)
    }

    func testBanglaIsLeftToRight() {
        XCTAssertFalse(LocalizedText.isRightToLeft("আমার সোনার বাংলা"))
    }

    func testArabicIsRightToLeft() {
        XCTAssertTrue(LocalizedText.isRightToLeft("مرحبا بالعالم"))
    }

    func testLeadingNeutralsDoNotDecideDirection() {
        XCTAssertTrue(LocalizedText.isRightToLeft("  (مرحبا)"))
        XCTAssertFalse(LocalizedText.isRightToLeft("  123 Hello"))
    }

    func testMixedLineFollowsFirstStrongCharacter() {
        XCTAssertFalse(LocalizedText.isRightToLeft("Hello مرحبا"))
        XCTAssertTrue(LocalizedText.isRightToLeft("مرحبا Hello"))
    }
}
