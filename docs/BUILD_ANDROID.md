# Building the Android app

Guftugu is a single-module Kotlin + Jetpack Compose app in `android/`. It is not
distributed through an app store: you build the APK yourself and install it on
each phone. No Android Studio is required — the command line is enough.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | **17** | `java -version` must print 17.x. Temurin, OpenJDK or the JDK bundled with Android Studio all work. |
| Android SDK | platform **36** + build-tools 36.0.0 | Install with `sdkmanager` (command-line tools) or Android Studio. |
| Gradle | 8.14 (wrapper) | Downloaded automatically by `./gradlew` on first run. |
| Internet | first build only | Dependencies are fetched from Google Maven and Maven Central. |

Toolchain versions are pinned in `android/gradle/libs.versions.toml` (AGP 8.13.1,
Kotlin 2.3.20, KSP 2.3.12, Compose BOM 2026.06.01) and `android/gradle/wrapper/gradle-wrapper.properties`.

### Installing the SDK without Android Studio

```bash
# 1. command-line tools: https://developer.android.com/studio#command-line-tools-only
mkdir -p ~/Android/Sdk/cmdline-tools
unzip commandlinetools-*.zip -d ~/Android/Sdk/cmdline-tools
mv ~/Android/Sdk/cmdline-tools/cmdline-tools ~/Android/Sdk/cmdline-tools/latest

# 2. platform + build tools + adb
~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager \
  "platforms;android-36" "build-tools;36.0.0" "platform-tools"
yes | ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --licenses
```

### `local.properties`

Tell Gradle where the SDK is (this file is git-ignored; create it once):

```properties
# android/local.properties
sdk.dir=/home/you/Android/Sdk
```

(Alternatively export `ANDROID_HOME=/home/you/Android/Sdk`.)

## Debug build

```bash
cd android
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

The first run downloads Gradle and all dependencies (several minutes); later
builds are incremental. Unit tests (pure JVM: crypto, protocol DTOs):

```bash
./gradlew :app:testDebugUnitTest
```

## Installing on a phone

1. On the phone enable **Developer options → USB debugging** (or wireless debugging).
2. Connect it and install:

```bash
adb devices                       # the phone must show as "device"
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to the phone and open it — Android asks once to allow installs
from that source. Every phone that should chat needs the APK plus its own
invite (below).

## Release build (signed)

A release APK must be signed with your own key. Generate one once and keep it
safe — updates must be signed with the same key or Android refuses to install
them over the old version.

```bash
keytool -genkeypair -v \
  -keystore android/guftugu-release.jks \
  -alias guftugu -keyalg RSA -keysize 4096 -validity 10000
```

Then create `android/keystore.properties` (git-ignored, never commit it):

```properties
storeFile=guftugu-release.jks          # relative to the android/ directory
storePassword=********
keyAlias=guftugu
keyPassword=********
```

`app/build.gradle.kts` reads this file if it exists and wires it into
`signingConfigs.release`; without it, `assembleRelease` produces an unsigned APK.

```bash
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

Release builds are minified with R8 and resource-shrunk (rules in
`app/proguard-rules.pro`) and ship only `arm64-v8a` + `armeabi-v7a` natives,
which matters: the debug build cold-starts in ~10 s on a 2020 budget phone
because of debug-runtime overhead, the release build in ~2 s. Always hand out
the **release** APK. Keep the keystore safe — updates must be signed with the
same key or phones will refuse to install them (and every phone would have to
re-enrol after a reinstall).

### Bake your server into the build (recommended)

People who get your APK should only ever type an invite code. Put your server's
API URL (the `ApiUrl` output of the stack) in `android/local.properties`
(git-ignored):

```properties
guftugu.serverUrl=https://abc123.execute-api.us-west-2.amazonaws.com
# optional: where people can download the APK; added to invite messages
guftugu.downloadUrl=https://example.com/guftugu.apk
```

Without `guftugu.serverUrl` the app asks for a server address when joining.

### Emulator builds

Release and debug builds ship ARM natives only. For an x86_64 emulator:

```bash
./gradlew assembleDebug -Pguftugu.abis=x86_64
```

## Pointing the app at your server

Nothing about the server is compiled into the APK. The first screen asks to
**join a server**, and the server address travels inside the invite:

1. The admin creates an invite on their computer (see `docs/ADMIN_CLI.md`):
   `cd server && npm run admin -- invite --name "Ammi"`
   which prints a code like `GFT-7K3M-Q9XD`, a link
   `guftugu://join?api=https://…&code=GFT-7K3M-Q9XD`, and a QR code.
2. On the phone: scan the QR, open the link, or type the server URL and the
   code by hand. The app fetches `GET /.well-known/guftugu` to validate the
   server and learn its WebSocket URL, then enrols the phone.
3. An already-enrolled phone can invite a second phone of the same user from
   **Settings → Link another phone** (a 24 h link code).

Switching servers, moving to a new server URL, or adding TURN servers for calls
never requires a rebuild: the server sends its `ClientConfig` at login, and the
admin changes it with the CLI.

## Development against a local server

The app allows cleartext `http://` only for `localhost`, `127.0.0.1` and
`10.0.2.2` (the emulator's alias for the host machine) — see
`app/src/main/res/xml/network_security_config.xml`. Everything else must be `https://`.

## Troubleshooting

- **`SDK location not found`** — create `android/local.properties` (above).
- **`requires compile SDK 37` / `requires AGP 9.x`** — a dependency was bumped past
  what the pinned toolchain supports; either revert the bump or upgrade AGP and
  `compileSdk` together (see the comment at the top of `gradle/libs.versions.toml`).
- **`Unsupported class file major version`** — `java` is not JDK 17; set
  `JAVA_HOME` to a 17 install.
- **Install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`** — the phone has a
  build signed with a different key (e.g. debug vs release); uninstall first:
  `adb uninstall com.guftugu.app`.
