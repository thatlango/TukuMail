#!/usr/bin/env python3
import base64
import json
import os
import socket
import time
import urllib.error
import urllib.request

BASE = os.environ.get("ENGINE_TEST_BASE", "http://127.0.0.1:8088")
KEY = os.environ.get("ENGINE_ADMIN_KEY", "ci-engine-v2-key-at-least-24-chars")
DOMAIN = "engine-v2.test"
ADDRESS = "alice@" + DOMAIN
PASSWORD = "correct-horse-battery-staple"

def call(method, path, body=None):
    headers = {"X-Tuku-Engine-Key": KEY, "Accept": "application/json"}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, method=method, headers=headers, data=data)
    with urllib.request.urlopen(req, timeout=10) as resp:
        raw = resp.read()
        return resp.status, json.loads(raw) if raw else None

def wait_health():
    deadline = time.time() + 30
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(BASE + "/health", timeout=2) as resp:
                if resp.status == 200:
                    return
        except Exception:
            pass
        time.sleep(0.5)
    raise RuntimeError("engine health never became ready")

def smtp_dialog(lines):
    with socket.create_connection(("127.0.0.1", 2525), timeout=5) as sock:
        file = sock.makefile("rwb", buffering=0)
        greeting = file.readline().decode()
        assert greeting.startswith("220"), greeting
        responses = []
        for line in lines:
            file.write((line + "\r\n").encode())
            response = file.readline().decode()
            while response[:3].isdigit() and len(response) > 3 and response[3] == "-":
                response = file.readline().decode()
            responses.append(response)
        return responses

wait_health()

status, _ = call("PUT", "/v1/domains/" + DOMAIN)
assert status == 204

status, _ = call("PUT", "/v1/mailboxes/" + ADDRESS, {
    "displayName": "Alice Test",
    "password": PASSWORD,
    "quotaBytes": 10 * 1024 * 1024
})
assert status == 204

status, auth = call("POST", "/v1/auth", {"address": ADDRESS, "password": PASSWORD})
assert status == 200 and auth["authenticated"] is True

# Internet-style inbound SMTP to a local mailbox requires no auth.
responses = smtp_dialog([
    "EHLO ci.example",
    "MAIL FROM:<sender@outside.test>",
    "RCPT TO:<" + ADDRESS + ">",
    "DATA",
    "Subject: Engine v2 protocol smoke",
    "",
    "hello from smtp",
    ".",
    "QUIT",
])
assert responses[0].startswith("250"), responses
assert responses[1].startswith("250"), responses
assert responses[2].startswith("250"), responses
assert responses[3].startswith("354"), responses
# DATA helper treats each line as a command, so run DATA body in a dedicated socket below.

# Proper DATA transaction.
with socket.create_connection(("127.0.0.1", 2525), timeout=5) as sock:
    f = sock.makefile("rwb", buffering=0)
    assert f.readline().startswith(b"220")
    f.write(b"EHLO ci.example\r\n")
    while True:
        r = f.readline()
        if not (len(r) > 3 and r[3:4] == b"-"):
            break
    f.write(b"MAIL FROM:<sender@outside.test>\r\n"); assert f.readline().startswith(b"250")
    f.write(("RCPT TO:<" + ADDRESS + ">\r\n").encode()); assert f.readline().startswith(b"250")
    f.write(b"DATA\r\n"); assert f.readline().startswith(b"354")
    f.write(b"From: sender@outside.test\r\n")
    f.write(("To: " + ADDRESS + "\r\n").encode())
    f.write(b"Subject: Engine v2 protocol smoke\r\n\r\n")
    f.write(b"hello from smtp\r\n.\r\n")
    assert f.readline().startswith(b"250")
    f.write(b"QUIT\r\n"); assert f.readline().startswith(b"221")

status, messages = call("GET", "/v1/mailboxes/" + ADDRESS + "/messages?limit=10")
assert status == 200
hit = next(m for m in messages if m["subject"] == "Engine v2 protocol smoke")
assert "hello from smtp" in hit["preview"]

# Read through the native IMAP pilot.
with socket.create_connection(("127.0.0.1", 2143), timeout=5) as sock:
    f = sock.makefile("rwb", buffering=0)
    assert f.readline().startswith(b"* OK")
    f.write(('a1 LOGIN "' + ADDRESS + '" "' + PASSWORD + '"\r\n').encode())
    assert f.readline().startswith(b"a1 OK")
    f.write(b"a2 SELECT INBOX\r\n")
    select_lines = []
    while True:
        line = f.readline().decode()
        select_lines.append(line)
        if line.startswith("a2 "):
            break
    assert any("EXISTS" in line for line in select_lines)
    assert select_lines[-1].startswith("a2 OK")
    f.write(b"a3 SEARCH ALL\r\n")
    search = f.readline().decode()
    done = f.readline().decode()
    assert search.startswith("* SEARCH")
    assert done.startswith("a3 OK")
    f.write(b"a4 LOGOUT\r\n")
    assert f.readline().startswith(b"* BYE")
    assert f.readline().startswith(b"a4 OK")

# Verify relay denial for unauthenticated outside->outside traffic.
responses = smtp_dialog([
    "EHLO ci.example",
    "MAIL FROM:<sender@outside.test>",
    "RCPT TO:<recipient@outside.test>",
    "QUIT",
])
assert responses[2].startswith("550"), responses

# Verify authenticated submission can accept a remote recipient and queue it.
payload = base64.b64encode(("\0" + ADDRESS + "\0" + PASSWORD).encode()).decode()
responses = smtp_dialog([
    "EHLO ci.example",
    "AUTH PLAIN " + payload,
    "MAIL FROM:<" + ADDRESS + ">",
    "RCPT TO:<recipient@outside.test>",
    "QUIT",
])
assert responses[1].startswith("235"), responses
assert responses[2].startswith("250"), responses
assert responses[3].startswith("250"), responses

print("Tuku Engine v2 integration smoke: PASS")
