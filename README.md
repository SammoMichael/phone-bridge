# Phone Bridge

Let your AI assistant operate your Android phone. An accessibility-service app polls a command queue on your server, executes taps/swipes/typing/screenshots, and posts results back. No inbound ports — the phone only makes outgoing HTTPS calls, so it works behind NAT and firewalls.

Built so Muse can do phone-only things that can't be done in a VM browser: mobile-only apps, SMS verification flows, apps that need a real device, anything that requires your actual phone.

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

- **AI assistant phone control**: Let Muse (or any agent) operate mobile-only apps, handle SMS verification, and do anything that needs your real phone instead of a VM browser.
- **Travel**: Operate region-locked apps from your laptop while the phone sits in your pocket.
- **Accessibility**: Drive the phone for someone who can't use the touchscreen.
- **QA/Testing**: Scripted UI testing on real devices without USB.
- **Automation**: Anything you'd tap through manually, scripted.

## For AI assistants

If you're an AI agent setting this up to operate a phone on your user's behalf, here's the operating pattern that actually works — including the things we learned the hard way.

### The async command pattern

This is not request/response. You enqueue a command, the phone polls for it on its own schedule, executes it, and posts the result back. Your driver loop looks like this:

```
1. POST /enqueue {"action": "tap", "x": 540, "y": 1200}
   → returns {"id": "cmd-123"} immediately (NOT the result)
2. Poll GET /result/cmd-123 until {"status": "done"}
   → returns {"ok": true} or the action's payload
```

`pb.py` handles this for you, but if you're building your own driver, don't treat enqueue as synchronous. Commands typically execute within 2–5 seconds, but poll with a timeout.

### The operate loop

Every phone task follows the same loop:

```
1. screenshot (or dump) → see current state
2. Analyze: what screen am I on? What's the next action?
3. Enqueue ONE action (tap, type, swipe, etc.)
4. Wait for result, then screenshot again to verify the state changed as expected
5. Repeat until done
```

One action at a time. Don't batch multiple taps without verifying — if the first tap missed, everything after it is wrong. The screenshot-after-every-action discipline is what makes this reliable.

### Save layout maps per app

After you've navigated an app once, save the coordinates of key UI elements to a file (e.g. `layout-maps/wechat.md`). Next time, you go straight to the coordinates instead of dumping and guessing. This is the single biggest speedup.

Example:
```markdown
## WeChat
- Tab bar: Chats (150, 2230), Contacts (450, 2230), Discover (750, 2230), Me (970, 2230)
- Me → Pay and Services: (540, 700)
```

**Always `dump` for exact coordinates. Never guess from screenshots.** Screenshots are for understanding layout; the accessibility dump gives you pixel-exact bounds. A tap at guessed coordinates will miss.

### Taps are not blocked — verify, don't assume

Early testing suggested some apps (WeChat, Alipay, Meituan, Didi) block accessibility taps. That was never rigorously proven — it was a guess written up as fact. Android's accessibility framework exists for blind users; a complete block would break TalkBack. Don't assume blockage. If a tap doesn't work, check your coordinates first (always `dump` for exact bounds), verify with a screenshot, and only then investigate further.

### Practical lessons

- **`type` needs the field focused first.** Tap the input field, then send `type`. If `type` doesn't work, verify the field actually has focus (screenshot) before assuming the app is at fault.
- **The app stops checking in.** About 45 minutes after opening, the poller goes quiet even with battery set to Unrestricted. If commands stop executing, the fix is opening the app on the phone again. Check in with your user if it's been a while.
- **WebViews are inconsistent.** Some expose their contents to the accessibility tree, some are opaque. When `dump` returns nothing useful, fall back to screenshots + coordinate taps.
- **Screenshots need Android 11+.** On older versions, you're limited to `dump` for state.
- **Confirm before money moves.** Navigating, filling forms, and reading screens is fine to do autonomously. Anything that spends money, sends a message, or makes a booking needs the user's explicit go-ahead — every time, not just the first time.

### Example session

```
You: screenshot → see WeChat home, on Chats tab
You: tap (970, 2230) → Me tab
You: screenshot → verify Me tab open
You: tap (540, 700) → Pay and Services
You: screenshot → verify payment screen
You: "I can see your WeChat Pay balance. Want me to...?"
```

That's the pattern. See state, act once, verify, repeat — and ask before anything irreversible.

## Known limits

- Screenshots need Android 11+ (`AccessibilityService.takeScreenshot`).
- `type` needs the field focused first (tap it, then type).
- WebViews vary: some expose their contents to the accessibility tree, some don't. Use screenshots + coordinate taps as a fallback.
- Queue/results are in-memory; a server restart drops them.
- The app must stay alive — Android battery optimization can kill the poller. Set battery to Unrestricted and keep the app in recents.

## License

MIT. See [LICENSE](LICENSE).
