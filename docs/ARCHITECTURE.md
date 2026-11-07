# Architecture

[Documentation](README.md) / Development

Zefir shares its UI and application logic between Android, Windows and Linux using Kotlin and Compose Multiplatform. Platform implementations handle VPN permissions, tunnel lifecycle, storage and operating-system integration.

## Repository map

| Directory | Responsibility |
| --- | --- |
| `shared/src/commonMain/` | UI, application state, subscriptions, routing and core API |
| `shared/src/androidMain/` | Android platform implementation |
| `shared/src/desktopMain/` | Desktop platform implementation, core IPC and system proxy |
| `shared/src/desktopTest/` | Shared logic and Compose UI tests |
| `androidApp/` | Android activity, VPN service and Quick Settings tile |
| `desktopApp/` | Desktop entry point and Windows and Linux packaging |
| `installer/` | Custom Windows installer written in Go |
| `scripts/` | Native core preparation, signing and release validation |
| `docs/` | User and contributor documentation |
| `licenses/` | Third-party license texts |

Generated binaries live in `core/prebuilt/` and are excluded from Git. Local build output, reference checkouts and temporary files are also excluded.

## Application and core

The shared controller coordinates profiles, settings and connection state through the platform interface. The core API sends requests to the native network engine and receives results, logs and connection data.

The engine is mihomo, built through FlClash's Go wrapper. The FlClash revision is pinned in [scripts/core.env](../scripts/core.env); its submodule pins the mihomo revision. Local patches live in [scripts/core-patches/](../scripts/core-patches/). Sources and attribution are recorded in [NOTICE](../NOTICE).

| Platform | Core connection | Tunnel control |
| --- | --- | --- |
| Android | Generated gomobile Java/JNI bridge | Android VPN service |
| Windows | Separate core process over a loopback socket | TUN with elevation, or system proxy |
| Linux | Separate core process over a loopback socket | GNOME or KDE system proxy |

The wrapper is a dependency; Zefir's UI and application logic live in this repository.

## Data

Profiles, settings and logs are stored locally. The app builds a runtime configuration from the selected profile and app settings before applying it to the core. Subscription updates and diagnostic requests are described in [Privacy](PRIVACY.md).

## Builds

The native core is built on Linux. Android packages include the Java/JNI bridge; Windows and Linux packages include the core executable and Java runtime. The tagged release workflow builds and validates assets before publishing them.

See [Building](BUILDING.md) for local commands and [Releasing](RELEASING.md) for the release workflow.
