# Privacy

[Documentation](README.md) / Using Zefir

Zefir does not require an account and does not include analytics or automatic crash reporting. Profiles, settings and connection logs are stored on your device.

## Local data

Profiles and backups can contain subscription URLs, server credentials, private keys and routing rules. Logs can contain hostnames, IP addresses and connection errors. Review exports before sharing them, even if the app has removed some sensitive fields.

Data locations and backup instructions are in the [user guide](USAGE.md#data-and-backups).

## Network requests

Requests depend on the features available in your build and the configuration you use.

| Feature | Destination and data |
| --- | --- |
| VPN connection | The selected server receives connection traffic according to your routing rules |
| Subscription updates | Your provider receives the subscription request and any configured client or device headers |
| DNS | Resolvers selected by the profile or app settings receive DNS queries |
| Latency and connectivity checks | The configured test endpoint; the default uses `www.gstatic.com` |
| Exit IP checks | `ipinfo.io`, `api.ip.sb` and `ipwho.is` receive requests to determine the exit address |
| Speed test | `speed.cloudflare.com` receives test traffic; a test can use substantial bandwidth |
| Site routing diagnostics | The domain you enter receives a probe request |
| Update check | GitHub receives a request for the repository's release list |
| Remote rule sets and proxy providers | The URLs configured in your profile receive update requests |

Device headers may include a device identifier, model and OS version. Builds with the **Send device data** setting let you turn these headers off; some providers require them for device-bound subscriptions.

Earlier builds with the experimental assistant may also send search queries to DuckDuckGo, Bing and Wikipedia, and keep assistant history locally.

## Android permissions

- VPN permission creates the tunnel used to route traffic.
- Installed-app access supports per-app routing.
- Location permission is used for Wi-Fi-based automatic rules when enabled.
- QR scanning uses Google components when available; builds with the fallback scanner use the camera directly.
- Notifications show connection status and controls.

Zefir does not operate your VPN provider, DNS resolver or diagnostic services. Their handling of requests follows their own policies.
