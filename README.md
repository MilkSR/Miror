# Miror

Miror is a Kotlin Multiplatform card scanner and collection manager for Pokemon TCG collectors. Shared Compose Multiplatform code powers collection, pricing, backup, and UI flows on Android and iOS.

## Source-only release

This repository intentionally excludes Miror's proprietary models and related private assets. It also excludes private training data, signing credentials, and internal development workspaces. Some scanner functionality is therefore unavailable in public builds.

## Project layout

```text
composeApp/
  src/commonMain/      shared Compose UI, data, pricing, and collection logic
  src/androidMain/     Android host, CameraX, Room, and platform integrations
  src/androidDebug/    Android-only diagnostic and validation tools
  src/androidRelease/  release-specific wiring
  src/iosMain/         iOS implementations of shared platform APIs

iosApp/
  iosApp/              Swift application host and native camera code
  Configuration/       local Xcode build settings
  Podfile              native iOS dependencies

gradle/                Gradle wrapper and version catalog
```

## Android setup and build

Android builds work on Windows, macOS, or Linux.

### Requirements

- Android Studio with the Android SDK
- JDK 17 (Android Studio's bundled JDK is suitable)
- Android SDK Platform 36
- Android SDK Build Tools for API 36
- Android NDK `29.0.14033849`
- CMake 3.18.1 or newer from Android Studio's SDK Manager

In Android Studio, install the SDK and NDK components from **Tools -> SDK Manager**, then open the repository root and allow Gradle to sync. Android Studio normally creates `local.properties` with your SDK path; this machine-local file is intentionally not committed.

From a terminal at the repository root:

```powershell
# Windows
.\gradlew.bat :composeApp:assembleDebug
```

```bash
# macOS or Linux
./gradlew :composeApp:assembleDebug
```

The debug APK is written below `composeApp/build/outputs/apk/debug/`.

Other useful tasks:

```bash
./gradlew :composeApp:test
./gradlew :composeApp:assembleRelease
./gradlew :composeApp:bundleRelease
```

Use `gradlew.bat` instead of `./gradlew` on Windows. Release signing is not configured in this public source tree; configure your own signing setup before distributing an APK or App Bundle.

## iOS setup and build

iOS builds require macOS because Xcode and the iOS SDK are only available there.

### Requirements

- A current Xcode release with the iOS 16 SDK or newer
- Xcode command-line tools (`xcode-select --install`)
- JDK 17 for the Gradle/Kotlin framework build
- CocoaPods 1.16 or newer

Install CocoaPods using either Homebrew or RubyGems:

```bash
brew install cocoapods
```

or:

```bash
sudo gem install cocoapods
```

Install the native iOS dependencies and open the generated workspace:

```bash
cd iosApp
pod install
open iosApp.xcworkspace
```

Always open `iosApp.xcworkspace`, not `iosApp.xcodeproj`, after running CocoaPods. The workspace contains both the Miror app and its installed pods.

Before building:

1. Open the `iosApp` target's **Signing & Capabilities** settings.
2. Select your Apple development team and choose a bundle identifier you control.
3. Alternatively, fill in `TEAM_ID` and adjust `PRODUCT_BUNDLE_IDENTIFIER` in `iosApp/Configuration/Config.xcconfig`.
4. Select an iOS simulator or connected device and run the app from Xcode.

The Xcode project invokes `:composeApp:embedAndSignAppleFrameworkForXcode` automatically to build and embed the shared Kotlin framework. Apple Silicon simulators and arm64 devices are configured. A physical device requires a valid signing team.

If CocoaPods reports that its sandbox is out of sync, run `pod install` again from `iosApp/`. When changing pod versions, use `pod update` deliberately and review the resulting `Podfile.lock` changes.

## Privacy

No proprietary model assets, API keys, signing keys, local SDK paths, personal collection data, or analytics credentials are included in this source tree.

## License

This project is source-available under the [PolyForm Perimeter License 1.0.1](LICENSE), not an open-source license. You may inspect, modify, contribute to, and distribute the code for permitted purposes. You may not use it to provide a competing product, including a free, rebranded, replatformed, or otherwise substitute app.
