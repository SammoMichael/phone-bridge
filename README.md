# Phone Bridge

Remote-control your own Android phone from anywhere. An accessibility-service app polls a command queue on your server, executes taps/swipes/typing/screenshots, and posts results back. No inbound ports — the phone only makes outgoing HTTPS calls, so it works behind NAT, firewalls, and the Great Firewall.

Built for a month-long China trip where the laptop couldn't stay on. The phone is the computer you already carry.

## How it works

```
Your script → Queue server (HTTPS) → Phone app (polls /next)
                                              ↓
                                    AccessibilityService
                                    (tap, swipe, type, dump UI, screenshot)
                                              ↓
                                    Phone app posts to /result → Your script
```

- **App**: Android (Java, minSdk 26, zero dependencies). Foreground service long-polls the server, executes commands via `AccessibilityService`.
- **Server**: Python stdlib-only queue server. Deploy anywhere with HTTPS.
- **Driver**: Enqueue commands, fetch results. Drive the phone programmatically.

## Quick start

See [docs/SETUP.md](docs/SETUP.md) for full instructions.

1. Deploy `server/queue_server.py` with HTTPS (nginx + Let's Encrypt).
2. Build the APK: `cd app && ./build.sh` (needs Android SDK, no Gradle/Studio).
3. Install on phone, enter server URL + token, enable the accessibility service.
4. Drive it: `python3 server/pb.py --tap 540 1200` (see `pb.py --help`).

## Protocol

| action | fields | result |
|---|---|---|
| `tap` | `x`, `y` (pixels) | `{ok}` |
| `swipe` | `x1`,`y1`,`x2`,`y2`,`ms` | `{ok}` |
| `type` | `text` (any language, incl. CJK) | `{ok}` — tap the field first to focus it |
| `key` | `back`\|`home`\|`recents` | `{ok}` |
| `open` | `package` (e.g. `com.tencent.mm`) | `{ok}` |
| `dump` | — | `{ok, nodes:[...]}` — accessibility tree with text, bounds, flags |
| `screenshot` | — | `{ok, image:<base64 jpeg>}` — Android 11+ |
| `wait` | `ms` | `{ok}` |

See [docs/PROTOCOL.md](docs/PROTOCOL.md) for details.

## Security

This is a remote-control tool. Treat it like SSH access to your phone:

- One shared secret token, constant-time compared on every request. Keep it in a file with `600` permissions, never in the repo.
- HTTPS required. The app pins nothing; use a proper cert.
- The app stores only the server URL + token. Your WeChat/Alipay/bank logins stay on the phone — never pass credentials through the queue.
- Revoke instantly: stop the poller, disable the accessibility service, or uninstall. Any one ends remote control.

See [docs/SECURITY.md](docs/SECURITY.md) for the threat model.

## Use cases

- **Travel**: Operate region-locked apps (WeChat, Alipay, 12306, Meituan) from your laptop while the phone sits in your pocket.
- **Accessibility**: Drive the phone for someone who can't use the touchscreen.
- **QA/Testing**: Scripted UI testing on real devices without USB.
- **Automation**: Anything you'd tap through manually, scripted.

## Known limits

- Screenshots need Android 11+ (`AccessibilityService.takeScreenshot`).
- `type` needs the field focused first (tap it, then type). Some apps (WeChat) block programmatic input in custom fields — use ADB `input text` as a fallback when you have USB/wireless debugging.
- WebViews vary: some expose their contents to the accessibility tree, some don't. Use screenshots + coordinate taps as a fallback.
- Queue/results are in-memory; a server restart drops them.
- The app must stay alive — Android battery optimization can kill the poller. Set battery to Unrestricted and keep the app in recents.

## License

MIT. See [LICENSE](LICENSE).
