# Setup Guide

## Prerequisites

- An Android phone (Android 8.0+, API 26+; Android 11+ for screenshots)
- A server with a public HTTPS endpoint (VPS, cloud VM, etc.)
- Android SDK build tools (for building the APK; no Android Studio or Gradle needed)

## 1. Deploy the server

The server is stdlib-only Python 3. No dependencies to install.

```bash
# On your server:
mkdir -p /opt/phone-bridge
cp server/queue_server.py server/config.example.json /opt/phone-bridge/
cd /opt/phone-bridge
cp config.example.json config.json
# Edit config.json: set your token (generate with: openssl rand -hex 32)
# Set the port (default 18743)
```

**HTTPS is required.** The app will refuse plain HTTP in production. Options:
- nginx reverse proxy + Let's Encrypt (recommended)
- Cloudflare Tunnel (no open ports; see below)

### Option A: nginx + Let's Encrypt

```nginx
server {
    listen 443 ssl;
    server_name bridge.example.com;
    ssl_certificate /etc/letsencrypt/live/bridge.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/bridge.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:18743;
        proxy_set_header Host $host;
    }
}
```

### Option B: Cloudflare Tunnel

```yaml
# /etc/cloudflared/config.yml
tunnel: <your-tunnel-id>
ingress:
  - hostname: bridge.example.com
    service: http://127.0.0.1:18743
  - service: http_status:404
```

### Systemd

```bash
sudo cp server/phone-bridge.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now phone-bridge
```

## 2. Build the APK

You need the Android SDK build tools (`aapt2`, `d8`, `apksigner`). No Gradle, no Android Studio.

```bash
cd app
# Set ANDROID_SDK_ROOT if not already set
export ANDROID_SDK_ROOT=/path/to/android-sdk
./build.sh
# Output: phone-bridge.apk
```

The build script compiles the three Java files, packages resources, dexes, signs with a debug key, and zipaligns. See `app/build.sh` for details.

## 3. Install and configure the phone

1. Transfer `phone-bridge.apk` to the phone (USB, nearby share, etc.)
2. Install (allow "install unknown apps" if prompted)
3. Open the app:
   - Enter the **server URL** (e.g. `https://bridge.example.com`)
   - Enter the **token** (must match `config.json` on the server)
   - Tap **Start**
4. Enable the accessibility service:
   - Android Settings → Accessibility → Phone Bridge → Enable
   - This is what lets the app tap/swipe/type on your behalf
5. (Recommended) Battery → Set to **Unrestricted**, so Android doesn't kill the poller
6. Leave the app in recents. You'll see a "Phone Bridge — Polling" notification.

## 4. Drive it

```bash
# Enqueue a tap at (540, 1200):
python3 server/pb.py --server https://bridge.example.com --token $(cat ~/.phone-bridge-token) tap 540 1200

# Take a screenshot:
python3 server/pb.py --server https://bridge.example.com --token $(cat ~/.phone-bridge-token) screenshot --out /tmp/screen.jpg

# Dump the UI tree:
python3 server/pb.py --server https://bridge.example.com --token $(cat ~/.phone-bridge-token) dump
```

See `server/pb.py --help` for all commands. See [PROTOCOL.md](PROTOCOL.md) for the raw HTTP API.

## Troubleshooting

- **"No commands received"**: Check the token matches. Check the server is reachable via HTTPS. Check the app shows "Polling".
- **App stops polling**: Battery optimization. Set to Unrestricted. On some OEMs (Xiaomi, Huawei), also enable "Autostart".
- **Accessibility service disabled after reboot**: Normal Android behavior. Re-enable after reboot, or use an automation app to re-enable it.
- **`type` doesn't work in an app**: Tap the field first to focus it. If it still fails, the app may use a custom input field — try tapping it, waiting a moment, then `type` again.
- **Screenshot is black**: The app was in the background, or the screen was off. Screenshots capture the current foreground app.
