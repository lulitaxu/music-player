# The app currently uses platform JSON and MediaPlayer APIs only.
# Keep this file as the release shrinker extension point.

## Rules for NewPipeExtractor (from its README): Rhino runs YouTube's
## player JS for signature handling, so it must survive shrinking.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
## Rhino also references desktop-JDK APIs that do not exist on Android
## (JavaBeans introspection, JSR-223 scripting, dynalink). Those code
## paths are never exercised on Android; silence R8's missing-class
## errors for them.
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

## Rules for protobuf-javalite (transitive via NewPipeExtractor 0.26.5).
## The extractor jar ships GeneratedMessageLite subclasses for YouTube
## playlist continuation parsing
## (org.schabi.newpipe.extractor.services.youtube.protos.**).
## GeneratedMessageLite dispatches into generated classes BY NAME —
## it reflectively reads the static DEFAULT_INSTANCE field and invokes
## dynamicMethod(...) — and the protobuf-javalite 4.35.1 jar carries
## NO consumer rules of its own (verified: META-INF holds only
## MANIFEST.MF), so without this keep R8 renames those members and
## continuation parsing dies at runtime. This is protobuf's own
## official lite pattern, scoped to GeneratedMessageLite subclasses.
-keep class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <methods>;
}
