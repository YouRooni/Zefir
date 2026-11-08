# Building

[Documentation](README.md) / Development

Build the native core first, then the Android, Windows or Linux application. Native binaries are generated locally and are not committed.

## Requirements

| Tool | Version or source |
| --- | --- |
| JDK | 21 |
| Gradle | Repository wrapper |
| Android SDK | `platforms;android-37.0`, `build-tools;37.0.0` |
| Android NDK | Pinned in [scripts/core.env](../scripts/core.env) |
| Go for the core | Downloaded at the version pinned in [scripts/core.env](../scripts/core.env) |

Use Linux x64 for the core build and Linux packaging, and Windows x64 for Windows packaging. The Linux host needs Git, curl, tar, unzip, a C/C++ toolchain and the Android SDK. Set `JAVA_HOME` and `ANDROID_HOME`, accept SDK licenses and allow at least 8 GB of RAM for Gradle.

## 1. Prepare the native core

From the repository root on Linux:

```bash
source scripts/core.env
sdkmanager "ndk;$NDK_VERSION"
bash scripts/build-core.sh
bash scripts/prepare-core.sh
```

The scripts check out the pinned FlClash source and mihomo submodule, apply the patches in `scripts/core-patches/`, build the configured Android ABIs and the Windows and Linux executables, then verify and stage the routing databases.

The build workspace defaults to `tmp/core-build/`. Set `ROOT` to an absolute path to use another location. Prepared files are placed in:

```text
core/prebuilt/
  vpncore-classes.jar
  vpncore-windows-amd64.exe
  vpncore-linux-amd64
  jniLibs/<abi>/libgojni.so
  geo/GEOIP.metadb
  geo/GEOSITE.dat
  legal/
```

To package Windows, copy the entire `core/prebuilt/` directory to the same location in your Windows checkout. Keep the JNI libraries and notices, not just the Java bridge.

## 2. Build Android

Install the SDK packages and build:

```bash
sdkmanager "platforms;android-37.0" "build-tools;37.0.0"
./gradlew :androidApp:assembleDebug
```

On Windows, use `gradlew.bat`. If `ANDROID_HOME` is not set, create an ignored `local.properties` containing `sdk.dir` with your SDK path.

For a signed release, create an ignored `keystore.properties`:

```properties
KEYSTORE_FILE=release.jks
KEYSTORE_PASSWORD=your-store-password
KEY_ALIAS=your-key-alias
KEY_PASSWORD=your-key-password
```

Then run `./gradlew :androidApp:assembleRelease`. APKs are written to `androidApp/build/outputs/apk/release/`, one per configured ABI. Release builds use R8 and resource shrinking.

Zefir X, the Google Play build (`dev.rooni.zefir.x`), is the same app without device identity overrides. Add `-Pzefir.channel=play`, for example `./gradlew :androidApp:bundleRelease -Pzefir.channel=play`; the bundle is written to `androidApp/build/outputs/bundle/release/`.

Without a keystore, local release builds use the debug key. CI requires signing credentials. A self-built APK can update an installed APK only when their signing keys match.

## 3. Test and package Windows

Run on Windows with JDK 21 and the Android SDK installed; the shared module also configures an Android target.

```powershell
.\gradlew.bat :shared:desktopTest :desktopApp:packagePortable
```

The portable ZIP is written to `desktopApp/build/compose/binaries/main/portable/`. It contains the Java runtime, native core, routing databases and license notices.

Windows setup executables use the project's custom Go installer in `installer/`. Build it with Go installed: `.\gradlew.bat :desktopApp:packageInstaller`. Do not use WiX or `packageExe`. Installer packaging is separate from the portable release workflow; only assets uploaded by that workflow are published automatically.

## 4. Package Linux

Run on Linux x64 with JDK 21 and the Android SDK installed:

```bash
./gradlew :shared:desktopTest :desktopApp:packageLinux
```

The archive is written to `desktopApp/build/compose/binaries/main/linux/`. It contains the Java runtime, native core, routing databases, license notices and `install.sh`, which adds a menu shortcut for the current user. Compose packages the desktop app only for the host operating system.

## Common build problems

| Error | Check |
| --- | --- |
| Missing native core or bridge classes | Finish both core scripts and copy all of `core/prebuilt/` |
| Android SDK not found | Check `ANDROID_HOME` or `local.properties` and accept SDK licenses |
| Gradle runs out of memory | Preserve the repository JVM settings; save `gradle.properties` as UTF-8 without BOM |
| APK cannot update the installed app | Check the signing key and version code |

## Versions and release sources

[version.properties](../version.properties) controls the version name, Android version code and package metadata. See [Releasing](RELEASING.md) for signing setup and tags.

A complete release source archive includes the core under `third-party/FlClash/` with vendored Go dependencies. GitHub's automatic source ZIP contains only this repository. The normal core script expects a Git checkout and refreshes upstream sources; for the bundled source archive, use its build commands and flags directly against the included core tree.
