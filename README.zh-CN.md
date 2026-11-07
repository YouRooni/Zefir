<p align="center">
  <img src="docs/assets/zefir.svg" width="88" height="88" alt="Zefir 标志">
</p>

<h1 align="center">Zefir</h1>

<p align="center">简洁、美观的 VPN 客户端。<br>快速、私密、现代。</p>

<p align="center">
  <a href="README.md">English</a> · <a href="README.ru.md">Русский</a> · 简体中文
</p>

<p align="center">
  <a href="https://github.com/YouRooni/Zefir/releases">版本发布</a> ·
  <a href="docs/README.md">文档</a> ·
  <a href="https://github.com/YouRooni/Zefir/issues">问题反馈</a>
</p>

使用你自己的服务器或订阅。Zefir 管理连接、节点选择、DNS 和路由，不销售 VPN 服务。

## 功能

- **导入：**Clash YAML、Base64 订阅、节点分享链接，以及 WireGuard / AmneziaWG 配置文件。
- **连接：**配置管理、节点选择和延迟测试。通过 mihomo 支持 VLESS、VMess、Trojan、Shadowsocks、Hysteria、Hysteria 2、TUIC、WireGuard 和 AmneziaWG。
- **路由：**DNS 设置和流量规则、Android 按应用分流，Windows TUN 和系统代理，以及 Linux 系统代理。
- **诊断：**活动连接、流量、日志和网络检查。支持导出与恢复配置。

界面支持英语、俄语、简体中文、乌克兰语和白俄罗斯语。

## 开始使用

Zefir 目前处于测试阶段。可在 [Releases](https://github.com/YouRooni/Zefir/releases) 下载预构建安装包，也可以[从源码构建](docs/BUILDING.md)。

| 平台 | 要求 |
| --- | --- |
| Android | Android 8.0 或更高版本 |
| Windows | Windows 10/11，x64；打包版本已包含 Java |
| Linux | x64，使用 GNOME 或 KDE 系统代理设置；打包版本已包含 Java |

1. 安装 Android APK，解压 Windows 便携版并运行 `Zefir.exe`，或解压 Linux 压缩包并运行 `Zefir/bin/Zefir`。
2. 添加订阅地址、导入配置文件或粘贴节点链接。
3. 选择节点并连接。Android 需要 VPN 授权；Windows TUN 需要管理员权限。

安装包选择、数据目录和连接问题请参阅英文[使用指南](docs/USAGE.md)。

## 开发

界面和应用逻辑使用 Kotlin 与 Compose Multiplatform 编写。网络内核为 mihomo，通过 FlClash 的原生封装接入。

[构建指南](docs/BUILDING.md) · [架构](docs/ARCHITECTURE.md) · [参与贡献](CONTRIBUTING.md) · [发布流程](docs/RELEASING.md)

## 隐私与反馈

无需 Zefir 账号，没有内置统计分析或自动崩溃报告。网络请求取决于使用的功能和服务商，详见[隐私说明](docs/PRIVACY.md)。

请在 [Issues](https://github.com/YouRooni/Zefir/issues) 反馈问题。安全漏洞请按照 [SECURITY.md](SECURITY.md) 私下发送至 [Telegram @YouRooni](https://t.me/YouRooni) 或 [studio.rooni@gmail.com](mailto:studio.rooni@gmail.com)。分享日志或截图前，请删除订阅地址、令牌和密码。

## 许可证

[GNU GPL v3.0](LICENSE)。Copyright © 2026 Rooni and contributors. 不提供担保。第三方代码的许可证和署名见 [NOTICE](NOTICE)。

Author: [YouRooni.t.me](https://t.me/YouRooni)
