import Foundation

/// International text foundation. Bangla, Arabic, Hindi, and every
/// other script must render exactly as written, in metadata, search,
/// and above all lyrics. iOS's text stack shapes complex scripts
/// correctly on its own; this app's job is to not break it:
///
/// - Normalize to NFC at ingest, so combining signs arrive composed.
/// - Never force a Latin-only font onto content text (system fonts
///   carry the fallbacks, including Kohinoor Bangla).
/// - Decide direction per line from the first strong character, so
///   RTL lyric lines align right and LTR lines align left, even
///   mixed inside one song.
/// - Highlight karaoke by whole lines only; splitting grapheme
///   clusters for word animation would tear conjuncts apart.
public enum LocalizedText {

    /// Canonical composed form (NFC) for provider or file text.
    public static func normalize(_ text: String) -> String {
        text.precomposedStringWithCanonicalMapping
    }

    /// True when the line's first strong directional character belongs
    /// to an RTL script. Neutral characters (spaces, digits,
    /// punctuation) are skipped, matching bidi-algorithm behavior.
    public static func isRightToLeft(_ text: String) -> Bool {
        for scalar in text.unicodeScalars {
            switch scalar.value {
            case 0x0590...0x05FF,   // Hebrew
                 0x0600...0x06FF,   // Arabic
                 0x0750...0x077F,   // Arabic Supplement
                 0x08A0...0x08FF,   // Arabic Extended-A
                 0xFB50...0xFDFF,   // Arabic Presentation Forms-A
                 0xFE70...0xFEFF:   // Arabic Presentation Forms-B
                return true
            case 0x0041...0x005A,   // A-Z
                 0x0061...0x007A,   // a-z
                 0x00C0...0x024F,   // Latin Extended
                 0x0900...0x097F,   // Devanagari
                 0x0980...0x09FF,   // Bengali
                 0x4E00...0x9FFF,   // CJK
                 0x3040...0x30FF,   // Japanese kana
                 0xAC00...0xD7AF:   // Korean syllables
                return false
            default:
                continue
            }
        }
        return false
    }
}
