#!/usr/bin/env python3
"""Driver CLI for the Phone Bridge queue server (stdlib only).

Env: PB_URL (e.g. https://pb.example.com), PB_TOKEN (shared secret).

  pb.py enqueue '{"id":"c1","action":"dump"}'
  pb.py tap c2 500 800
  pb.py swipe c3 500 1200 500 400 400
  pb.py type c4 "你好"
  pb.py key c5 back            # back|home|recents
  pb.py open c6 com.tencent.mm # WeChat
  pb.py shot c7                # screenshot
  pb.py wait c8 1500
  pb.py result c1 [--timeout 60] [--save shot.jpg]
  pb.py health
"""
import base64
import json
import os
import sys
import time
import urllib.request
import urllib.parse

BASE = os.environ.get("PB_URL", "").rstrip("/")
TOKEN = os.environ.get("PB_TOKEN", "")


def call(method, path, body=None, query=None):
    if not BASE or not TOKEN:
        sys.exit("set PB_URL and PB_TOKEN first")
    url = BASE + path
    if query:
        url += "?" + urllib.parse.urlencode(query)
    data = None
    if body is not None:
        body = dict(body)
        body["token"] = TOKEN
        data = json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json",
                                          "User-Agent": "Mozilla/5.0 (Muse phone-bridge driver)"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"error": raw[:200]}


def enqueue(cmd):
    st, r = call("POST", "/enqueue", {"cmd": cmd})
    if st != 200:
        sys.exit("enqueue failed: %s %s" % (st, r))
    print("queued %s (depth %s)" % (cmd["id"], r.get("queued")))


def cmd_enqueue(cid, action, **kw):
    cmd = {"id": cid, "action": action}
    cmd.update(kw)
    enqueue(cmd)


def wait_result(cid, timeout=60, save=None):
    t0 = time.time()
    while time.time() - t0 < timeout:
        st, r = call("GET", "/result", query={"token": TOKEN, "id": cid})
        if st == 200:
            res = r["result"] or {}
            if save and res.get("image"):
                with open(save, "wb") as f:
                    f.write(base64.b64decode(res["image"]))
                print("screenshot saved to %s" % save)
                res = dict(res)
                res["image"] = "<saved to %s>" % save
            print(json.dumps(res, ensure_ascii=False, indent=1)[:4000])
            return
        time.sleep(2)
    sys.exit("timed out waiting for result %s" % cid)


def main():
    a = sys.argv[1:]
    if not a or a[0] == "health":
        print(call("GET", "/health"))
        return
    if a[0] == "enqueue":
        enqueue(json.loads(a[1]))
    elif a[0] == "tap":
        cmd_enqueue(a[1], "tap", x=int(a[2]), y=int(a[3]))
    elif a[0] == "swipe":
        cmd_enqueue(a[1], "swipe", x1=int(a[2]), y1=int(a[3]),
                    x2=int(a[4]), y2=int(a[5]), ms=int(a[6]) if len(a) > 6 else 400)
    elif a[0] == "type":
        cmd_enqueue(a[1], "type", text=a[2])
    elif a[0] == "key":
        cmd_enqueue(a[1], "key", name=a[2])
    elif a[0] == "open":
        cmd_enqueue(a[1], "open", package=a[2])
    elif a[0] == "shot":
        cmd_enqueue(a[1], "screenshot")
    elif a[0] == "wait":
        cmd_enqueue(a[1], "wait", ms=int(a[2]))
    elif a[0] == "result":
        timeout, save = 60, None
        rest = a[2:]
        i = 0
        while i < len(rest):
            if rest[i] == "--timeout":
                timeout = int(rest[i + 1]); i += 2
            elif rest[i] == "--save":
                save = rest[i + 1]; i += 2
            else:
                i += 1
        wait_result(a[1], timeout, save)
    else:
        sys.exit("unknown command: %s" % a[0])


if __name__ == "__main__":
    main()
