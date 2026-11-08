# Installation and troubleshooting

[Documentation](README.md) / Using Zefir

Zefir connects to servers you configure. You need a subscription URL, a server link or a supported configuration file from your provider or your own server.

## Choose a build

Pre-built packages are available in [Releases](https://github.com/YouRooni/Zefir/releases). You can also follow the [build guide](BUILDING.md) to create a local build. Choose from the assets attached to each version:

| Package suffix | Intended device |
| --- | --- |
| `android-arm64.apk` | Most current Android phones and tablets |
| `android-armv7.apk` | Android devices with 32-bit ARM support |
| `android-x86_64.apk` | Android x86_64 devices and emulators, when included in a release |
| `windows-x64-portable.zip` | Windows 10/11 x64; no separate Java installation needed |
| `windows-x64-setup.exe` | Windows installer, when included in a release |
| `linux-x64.tar.gz` | Linux x64; no separate Java installation needed |

Android requires version 8.0 or later. ARMv7 has not yet been tested on a physical device. Windows binaries are not code-signed. On Linux, the first TUN start asks for an administrator password to give the core the rights it needs. macOS and iOS are not supported.

## First connection

1. Install the APK on Android. For Windows portable builds, extract the **entire archive** to a writable folder and run `Zefir.exe`. If a setup executable is provided, use it to install the app. On Linux, extract the archive, run `Zefir/install.sh` to add a menu shortcut and start `Zefir/bin/Zefir`.
2. Add a subscription URL, import a configuration file or paste a server link.
3. Select the profile and server you want to use.
4. Connect and grant the requested VPN permission. Windows TUN needs administrator rights.

TUN routes device traffic through a virtual network interface, subject to your routing rules. System proxy mode on Windows and Linux covers applications that use the system proxy; it does not route all traffic from every application. On Linux, Zefir sets the GNOME (`gsettings`) and KDE proxy settings and restores the previous values when it disconnects.

## Data and backups

| Installation | Data location |
| --- | --- |
| Android | App-private storage |
| Windows portable | `data` beside `Zefir.exe` |
| Windows installed | `.zefir` in your user home folder, unless overridden |
| Linux | `.zefir` in your user home folder |

Use the app's backup export before moving to another device or clearing app data. Backups contain configuration and may include credentials. Store them privately; do not attach them to issues. See [Privacy](PRIVACY.md).

## Connection problems

| Symptom | Check |
| --- | --- |
| A subscription cannot be imported | Check the URL, expiry and provider availability. Never post the full URL in an issue. |
| One server fails | Check another server in the same profile, then review the connection log. |
| Connected, but a site does not open | Check the selected routing mode, custom rules and DNS settings. |
| Windows TUN does not start | Check whether the administrator request was accepted and whether another VPN is using a conflicting interface. |
| Some Windows apps ignore the VPN | Check whether you are using system proxy mode. Apps that ignore that proxy need TUN or their own proxy configuration. |
| Linux apps ignore the VPN | Check that your desktop uses GNOME or KDE proxy settings. On other desktops, set the HTTP or SOCKS proxy to `127.0.0.1` and the port shown in Settings. |
| Android disconnects in the background | Check system battery restrictions for Zefir and whether another VPN has taken over. |

## Report a problem

Search [existing issues](https://github.com/YouRooni/Zefir/issues) first. Include the Zefir version, OS version, connection mode, reproduction steps and expected result. For a failed import, name the format and use a minimal configuration with placeholders.

Review logs and screenshots before sharing them. Subscription URLs, QR codes, passwords, private keys and device identifiers can grant access or identify you. Report vulnerabilities through [Security](../SECURITY.md), not a normal bug report.
