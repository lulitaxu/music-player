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
