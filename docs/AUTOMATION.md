# Automation

[Documentation](README.md) / Using Zefir

Zefir on Android accepts commands from Tasker, MacroDroid, Automate, adb and scripts. Control is off by default. Turn it on in **Settings → Advanced → Control from other apps**. Plugin actions need nothing else; intents from adb and scripts must carry the key shown in that subsection.

## Plugin

Zefir is listed among plugins in Tasker, MacroDroid and other Locale hosts. Add the Zefir action, pick a command and fill its parameters. Plugin actions need no key, so imported macros work as soon as automation is on. Parameters accept host variables: `%name` in Tasker, `[v=name]` in MacroDroid.

After the action runs, the result is available in variables. Tasker variables start with `%` and end at the first character that is not a letter, digit or underscore. In MacroDroid, map them to your own variables in the action's Variable Mapping.

| Variable | Contents |
| --- | --- |
| `%zefir_ok` | `true` or `false` |
| `%zefir_error` | Why the command failed |
| `%zefir_result` | Result as JSON |
| `%zefir_json` | The whole reply |
| `%zefir_state`, `%zefir_server`, … | Each simple field of the result, for example from `status` |

## Sending a command

A command is a broadcast with the action `dev.rooni.zefir.COMMAND`, sent to the app package: `dev.rooni.zefir`, or `dev.rooni.zefir.x` for Zefir X. All parameters are string extras.

| Extra | Meaning |
| --- | --- |
| `key` | Key from the settings |
| `command` | Command name from the list below |
| `request_id` | Optional, returned unchanged in the reply |
| `reply_package` | Optional, the app that should receive the reply |
| other | Command arguments, such as `name` or `value` |

From a computer:

```bash
adb shell am broadcast -a dev.rooni.zefir.COMMAND -p dev.rooni.zefir --es key KEY --es command status
```

`am broadcast` prints the reply as `data="..."`. The same works from a root shell or Termux on the device.

**Tasker:** use the *Send Intent* action with the action `dev.rooni.zefir.COMMAND`, package `dev.rooni.zefir`, target *Broadcast Receiver*, and extras such as `key:KEY` and `command:connect`.

**MacroDroid:** use *Send Intent* with target *Broadcast*, the same action and package, and the extras as key/value pairs.

## Replies

Every reply is a JSON object:

```json
{"ok": true, "command": "status", "result": {"state": "on", "server": "Frankfurt"}}
```

On errors, `ok` is `false` and `error` describes the problem. With a valid key, Zefir also sends the broadcast `dev.rooni.zefir.RESULT` to `reply_package`, or, if none is given, to Tasker, MacroDroid and Automate when they are installed. Its extras are `command`, `ok`, `result` (JSON text), `error`, `json` (the whole reply) and `request_id`.

In Tasker, catch it with the *Intent Received* event for `dev.rooni.zefir.RESULT` and read fields with `%result.state`. In MacroDroid, use the *Intent Received* trigger and the *JSON Parse* action.

## Events

While automation is on, Zefir sends `dev.rooni.zefir.EVENT` to the same apps whenever the tunnel state, the current server or the active subscription changes. Extras: `event` (`state`), `state`, `server`, `profile` and `json`.

States: `off`, `starting`, `on`, `stopping` and `failed`.

## Commands

| Command | Arguments | Result |
| --- | --- | --- |
| `help` | | Command list |
| `status` | | State, subscription, server, mode, speed and traffic |
| `connect`, `disconnect`, `toggle`, `reconnect` | `wait=false` to return at once | Status after the change |
| `profiles` | | All subscriptions |
| `profile.activate` | `id` or `name` | The subscription |
| `profile.add` | `url` or `text` | The added subscription |
| `profile.refresh` | `id`, `name` or `all`; the active one by default | Updated subscriptions |
| `profile.delete` | `id` or `name` | The deleted subscription |
| `servers` | | Groups, servers and last delays |
| `server.select` | `name`, optional `group` | Group and server |
| `ping` | Optional `name`; all servers by default | Delays in ms, `-1` for unreachable |
| `latency` | | Delay without VPN and through the current server |
| `settings.get` | Optional `name` | All settings or one value |
| `settings.set` | `name`, `value` | New value |
| `rule.add` | `domain`, `action`: `direct`, `proxy` or `block` | The rule |
| `logs` | `count` (1–500), `redact=true` | Recent log lines |
| `connections` | | Open connections |
| `connections.close` | `id` or `all` | Remaining connections |
| `exit_ip` | | Public IP and country of the current route |
| `site` | `host` | Rule and exit used for a site |
| `services` | | Availability of popular services |
| `device` | | App version, build channel and device |

Commands that wait (connecting, refreshing, pinging) return after at most 20 seconds with whatever is ready.

`settings.set` takes the names returned by `settings.get`. Values are JSON (`true`, `1400`, `"Global"`, `["a","b"]`); text settings also accept plain text. Changes to routing, DNS and the tunnel restart the connection. Automation itself and the key can only be changed in the app.

## Examples

Connect when leaving home, in Tasker: a profile *Wi-Fi Connected (home)*, exit task *Send Intent* with `command:connect`.

Switch to the fastest server:

```bash
adb shell am broadcast -a dev.rooni.zefir.COMMAND -p dev.rooni.zefir --es key KEY --es command ping
adb shell am broadcast -a dev.rooni.zefir.COMMAND -p dev.rooni.zefir --es key KEY --es command server.select --es name "Frankfurt"
```

Turn on global mode:

```bash
adb shell am broadcast -a dev.rooni.zefir.COMMAND -p dev.rooni.zefir --es key KEY --es command settings.set --es name mode --es value Global
```

## Security

Any app on the device can send a broadcast, so intents without the correct key are rejected and get no reply broadcast. Plugin actions are accepted without the key while automation is on, so keep it off if you do not use it. Replies and events go only to known automation apps or to the package you name. Replace the key in the settings to revoke access for scripts at once. The key is not included in backups.

[Back to documentation](README.md)
