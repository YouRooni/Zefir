<p align="center">
  <img src="docs/assets/zefir.svg" width="88" height="88" alt="Zefir logo">
</p>

<h1 align="center">Zefir</h1>

<p align="center">A VPN client for Android, Windows and Linux.<br>mihomo networking · Material 3 Expressive</p>

<p align="center">
  English · <a href="README.ru.md">Русский</a> · <a href="README.zh-CN.md">简体中文</a>
</p>

<p align="center">
  <a href="https://github.com/YouRooni/Zefir/releases">Releases</a> ·
  <a href="docs/README.md">Documentation</a> ·
  <a href="https://github.com/YouRooni/Zefir/issues">Issues</a>
</p>

Use your own servers or subscriptions. Zefir handles the connection, server selection, DNS and routing; it does not sell VPN access.

## Features

- **Import:** Clash YAML, Base64 subscriptions, server links and WireGuard / AmneziaWG configuration files.
- **Connect:** profiles, server selection and latency checks, with VLESS, VMess, Trojan, Shadowsocks, Hysteria, Hysteria 2, TUIC, WireGuard and AmneziaWG through mihomo.
- **Route:** DNS settings and routing rules, per-app routing on Android, TUN and system proxy on Windows, system proxy on Linux.
- **Inspect:** active connections, traffic, logs and network diagnostics. Export and restore your configuration.

Available in English, Russian, Simplified Chinese, Ukrainian and Belarusian.

## Getting started

Zefir is in beta. No builds have been published to GitHub Releases yet; for now, [build from source](docs/BUILDING.md).

| Platform | Requirements |
| --- | --- |
| Android | Android 8.0 or later |
| Windows | Windows 10/11, x64; packaged builds include Java |
| Linux | x64 with GNOME or KDE system proxy settings; packaged builds include Java |

1. Install the Android APK, extract the Windows portable ZIP and run `Zefir.exe`, or extract the Linux archive and run `Zefir/bin/Zefir`.
2. Add a subscription URL, import a configuration file or paste a server link.
3. Select a server and connect. Android asks for VPN permission; Windows TUN requires administrator rights.

See [installation and troubleshooting](docs/USAGE.md) for package selection, data locations and connection problems.

## Development

The UI and application logic are written in Kotlin with Compose Multiplatform. The network engine is mihomo, connected through FlClash's native wrapper.

[Build instructions](docs/BUILDING.md) · [Architecture](docs/ARCHITECTURE.md) · [Contributing](CONTRIBUTING.md) · [Release process](docs/RELEASING.md)

## Privacy and support

No Zefir account, built-in analytics or automatic crash reports. Network requests depend on the features and providers you use: [privacy details](docs/PRIVACY.md).

Report bugs in [Issues](https://github.com/YouRooni/Zefir/issues). Send security reports privately to [Telegram @YouRooni](https://t.me/YouRooni) or [studio.rooni@gmail.com](mailto:studio.rooni@gmail.com), as described in [SECURITY.md](SECURITY.md). Remove subscription URLs, tokens and credentials before sharing logs or screenshots.

## License

[GNU GPL v3.0](LICENSE). Copyright © 2026 Rooni and contributors. Distributed without warranty. Third-party licenses and attribution are listed in [NOTICE](NOTICE).

Author: [YouRooni.t.me](https://t.me/YouRooni)
