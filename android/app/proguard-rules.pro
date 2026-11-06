# The app currently uses platform JSON and MediaPlayer APIs only.
# Keep this file as the release shrinker extension point.

## Rules for NewPipeExtractor (from its README): Rhino runs YouTube's
## player JS for signature handling, so it must survive shrinking.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
