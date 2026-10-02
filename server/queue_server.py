#!/usr/bin/env python3
"""Phone Bridge queue server. Python stdlib only — no pip dependencies.

The phone long-polls GET /next (outgoing HTTPS from the phone, so no inbound
ports are needed on the phone side). The driver enqueues commands via
POST /enqueue and fetches results via GET /result?id=...

Endpoints (all except /health need the shared token):
  GET  /health                 -> {"ok": true, "queued": n}
  GET  /next?token=...         -> one command JSON, or 204 if none within ~25s
  POST /enqueue  {"token","cmd":{"id","action",...}} -> {"ok": true}
  POST /result   {"token","id","result":{...}}       -> {"ok": true}
  GET  /result?token=...&id=... -> the stored result, or 404 if not arrived yet

Config: JSON file with {"token": "...", "port": 8080, "bind": "127.0.0.1",
"tls_cert": null, "tls_key": null}. Point PB_CONFIG at it, or pass --config.
Run behind nginx+certbot for public HTTPS, or set tls_cert/tls_key for direct TLS.
"""
import argparse
import hmac
import json
import os
import ssl
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

LONG_POLL_SECS = 25
MAX_RESULTS = 500
MAX_BODY = 2_000_000  # results may carry screenshots


def load_config(path):
    with open(path) as f:
        cfg = json.load(f)
    if not cfg.get("token") or len(cfg["token"]) < 16:
        sys.exit("config token missing or too short (use 32+ random hex chars)")
    return cfg


queue = []
results = {}
cond = threading.Condition()
TOKEN = ""


class Handler(BaseHTTPRequestHandler):
    server_version = "PhoneBridge/1"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), fmt % args))

    def _send(self, code, obj=None):
        body = b"" if obj is None else json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _authed(self, token):
        return bool(token) and hmac.compare_digest(token, TOKEN)

    def _read_json(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n > MAX_BODY:
            raise ValueError("body too large")
        raw = self.rfile.read(n) if n else b""
        return json.loads(raw.decode("utf-8")) if raw else {}

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        if u.path == "/health":
            with cond:
                n = len(queue)
            return self._send(200, {"ok": True, "queued": n})

        if u.path == "/next":
            if not self._authed(q.get("token", [""])[0]):
                return self._send(401, {"error": "bad token"})
            deadline = time.time() + LONG_POLL_SECS
            with cond:
                while True:
                    if queue:
                        return self._send(200, queue.pop(0))
                    remaining = deadline - time.time()
                    if remaining <= 0:
                        return self._send(204)
                    cond.wait(timeout=min(remaining, 5))

        if u.path == "/result":
            if not self._authed(q.get("token", [""])[0]):
                return self._send(401, {"error": "bad token"})
            rid = q.get("id", [""])[0]
            r = results.get(rid)
            if r is None:
                return self._send(404, {"error": "no result yet"})
            return self._send(200, r)

        return self._send(404, {"error": "not found"})

    def do_POST(self):
        u = urlparse(self.path)
        try:
            data = self._read_json()
        except Exception as e:
            return self._send(400, {"error": "bad json: %s" % e})
        if not self._authed(data.get("token")):
            return self._send(401, {"error": "bad token"})

        if u.path == "/enqueue":
            cmd = data.get("cmd")
            if not isinstance(cmd, dict) or "id" not in cmd or "action" not in cmd:
                return self._send(400, {"error": "cmd must be an object with id and action"})
            with cond:
                queue.append(cmd)
                cond.notify_all()
                n = len(queue)
            return self._send(200, {"ok": True, "queued": n})

        if u.path == "/result":
            rid = data.get("id")
            if not rid:
                return self._send(400, {"error": "id required"})
            with cond:
                results[rid] = {"id": rid, "at": time.time(), "result": data.get("result")}
                if len(results) > MAX_RESULTS:
                    old = sorted(results, key=lambda k: results[k]["at"])[: len(results) - MAX_RESULTS]
                    for k in old:
                        del results[k]
            return self._send(200, {"ok": True})

        return self._send(404, {"error": "not found"})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default=os.environ.get("PB_CONFIG", "/opt/phone-bridge/config.json"))
    args = ap.parse_args()
    global TOKEN
    cfg = load_config(args.config)
    TOKEN = cfg["token"]
    bind = cfg.get("bind", "127.0.0.1")
    port = int(cfg.get("port", 8080))
    server = ThreadingHTTPServer((bind, port), Handler)
    cert, key = cfg.get("tls_cert"), cfg.get("tls_key")
    scheme = "http"
    if cert and key:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(cert, key)
        server.socket = ctx.wrap_socket(server.socket, server_side=True)
        scheme = "https"
    print("phone-bridge queue on %s://%s:%d" % (scheme, bind, port), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
