# Protocol (v1)

All requests are HTTPS POST (or GET for `/next`) with JSON bodies. Every request includes the token.

## Authentication

Include the token in every request body as `"token"`. The server compares it with a constant-time check. Wrong token → `403`.

```json
{"token": "your-secret-token", "id": "cmd-1", "action": "tap", "x": 540, "y": 1200}
```

## Endpoints

### `POST /enqueue`

Queue a command. Returns `{"ok": true, "id": "cmd-1"}`.

| Field | Required | Description |
|---|---|---|
| `id` | yes | Client-chosen command ID (for correlating results) |
| `action` | yes | One of the actions below |
| `token` | yes | Shared secret |

### `GET /next?token=...`

Long-poll for the next command. The phone app calls this in a loop. Returns the oldest queued command, or `204` if the queue is empty (after a timeout).

### `POST /result`

The phone posts command results here.

```json
{"token": "...", "id": "cmd-1", "result": {"ok": true}}
```

### `GET /result?token=...&id=cmd-1`

Fetch a command's result. Returns `{"result": {...}}` if available, or `{"result": null}` if not yet posted.

## Actions

### `tap`

```json
{"action": "tap", "x": 540, "y": 1200}
```
Tap at pixel coordinates (device pixels, not dp). Result: `{"ok": true}` or `{"ok": false, "error": "tap_failed"}`.

Note: some apps don't report tap success reliably. Verify with a `screenshot` or `dump` after.

### `swipe`

```json
{"action": "swipe", "x1": 540, "y1": 1800, "x2": 540, "y2": 600, "ms": 300}
```
Swipe from `(x1,y1)` to `(x2,y2)` over `ms` milliseconds.

### `type`

```json
{"action": "type", "text": "hello world"}
```
Types text into the currently focused editable field. **Tap the field first** to focus it. Supports any language including CJK. Uses `AccessibilityNodeInfo.ACTION_SET_TEXT`.

Limitation: some apps with custom text fields (notably WeChat) ignore programmatic input. Fallback: ADB `input text` if you have debugging access.

### `key`

```json
{"action": "key", "name": "back"}
```
Press a system key. `name` is one of: `back`, `home`, `recents`.

### `open`

```json
{"action": "open", "package": "com.tencent.mm"}
```
Launch an app by package name. Returns `{"ok": false, "error": "no_launch_intent"}` if the package isn't installed or has no launcher.

Note: on Android 11+, the app needs `QUERY_ALL_PACKAGES` permission in the manifest to resolve launch intents for arbitrary packages.

### `dump`

```json
{"action": "dump"}
```
Returns the accessibility node tree:

```json
{
  "ok": true,
  "tree": "0;android.widget.FrameLayout;----;[0,0,1080,2400];;\n1;android.widget.TextView;C---;[100,200,500,300];Hello;\n..."
}
```

Each line: `depth;class;flags;[left,top,right,bottom];text;content-desc`

Flags: `C` = clickable, `E` = editable, `F` = focused, `S` = scrollable. `-` = no.

Note: some apps return an empty accessibility tree (custom rendering). Use `screenshot` instead.

### `screenshot`

```json
{"action": "screenshot"}
```
Returns:
```json
{"ok": true, "format": "jpeg", "image": "<base64>"}
```

Requires Android 11+ (API 30). Captures the current foreground app at half resolution to keep the payload small.

### `wait`

```json
{"action": "wait", "ms": 2000}
```
Sleep for `ms` milliseconds. Useful for waiting for page loads between actions.

## Typical drive loop

```
1. screenshot (or dump) → see the current screen
2. Decide: tap a button? type into a field?
3. tap / type / swipe
4. screenshot (or dump) → verify the action took effect
5. Repeat
```

For reliability: prefer `dump` when the app exposes its UI tree (gives exact coordinates). Fall back to `screenshot` + estimated coordinates when it doesn't. Always verify after each action — don't assume a tap landed.
