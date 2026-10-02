# Security Model

Phone Bridge is a **remote-control tool**. It gives whoever holds the token the ability to tap, type, and see everything on your phone. Treat the token like an SSH private key.

## Threat model

**What it protects against:**
- Random internet scanners (token required on every request)
- Passive network eavesdropping (HTTPS required)
- Accidental exposure (token is never in the app UI after entry, never logged)

**What it does NOT protect against:**
- Someone who steals your token (they get full control — rotate immediately)
- A compromised server (the server sees commands and screenshots)
- Malware on the phone (it can read the stored token)
- You (it does exactly what you tell it to, including typing passwords and confirming payments)

## Rules

1. **Token hygiene**
   - Generate with `openssl rand -hex 32`.
   - Store in a file with `600` permissions. Never commit it. The `.gitignore` blocks `*.token` and `config.json`.
   - If you suspect exposure: change `config.json` on the server, update the app, restart both.

2. **HTTPS only**
   - The app should refuse plain HTTP. In production, always use a valid TLS certificate (Let's Encrypt, Cloudflare Tunnel, etc.).
   - Self-signed certs work for testing but train you to ignore cert warnings. Don't.

3. **Credentials stay on the phone**
   - Never send usernames, passwords, or payment credentials through the command queue.
   - Log into WeChat/Alipay/banking apps **on the phone itself**. The bridge drives the UI; it never sees your passwords.
   - If you need the bridge to log in somewhere, use the phone's built-in password manager (autofill), not queued `type` commands with the password in plaintext.

4. **Money and messages need a human**
   - Recommended policy: the bridge navigates, fills forms, and reads screens. Anything that moves money, sends messages, or makes a purchase waits for explicit human confirmation.
   - Enforce this in your driver script, not in the bridge itself — the bridge is a dumb pipe.

5. **Revoke instantly**
   - Any one of these ends remote control immediately:
     - Stop the poller in the app
     - Disable the accessibility service (Settings → Accessibility)
     - Uninstall the app
     - Change the token on the server

6. **Server hardening**
   - The queue server is stdlib-only Python with no auth beyond the token. Put it behind a firewall that only allows 443 (or your tunnel).
   - Queue/results are in-memory. A restart wipes them — this is a feature (no persistent command history).
   - Don't run it as root. Use the provided systemd unit with `DynamicUser=yes` or a dedicated user.

## Abuse considerations

This tool can be misused (spam, fraud, account takeover). If you deploy it:

- Don't offer it as a service to strangers.
- Don't bridge phones you don't own.
- If you open-source a driver that automates a specific app, respect that app's ToS.

The authors assume no liability for misuse. See [LICENSE](../LICENSE).
