# arzxsh Android

This is the Android counterpart of the native iOS app. It targets Android
8.0+ and currently provides:

- Home trending tracks from Audius
- Search for Audius tracks
- Honest playable labels
- Authorized stream playback with play/pause
- Library, Downloads, and Settings empty states

## Professional build workflow

The repository includes a pinned Gradle wrapper. From the repository root:

```bash
cd android
./gradlew lintDebug testDebug
./gradlew assembleDebug
./gradlew assembleRelease
```

The debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`. The release build is minified
and resource-shrunk. It is intentionally unsigned unless a local
`keystore.properties` file is supplied.

For local release signing:

1. Copy `keystore.properties.example` to `keystore.properties`.
2. Create or obtain a release keystore outside Git.
3. Fill in the keystore path and passwords.
4. Run `./gradlew bundleRelease` for a Play Store `.aab`, or
   `./gradlew assembleRelease` for a directly installable signed APK.

Never commit keystores, passwords, or API credentials. GitHub Actions builds
the debug APK and an unsigned release APK on every push and pull request and
uploads both as workflow artifacts. Production signing should use protected
repository secrets or Google Play App Signing, not a checked-in key.
