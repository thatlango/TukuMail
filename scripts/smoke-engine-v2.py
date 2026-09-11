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

def read_smtp_response(file):
    response = file.readline()
    assert response, "SMTP connection closed"
    while len(response) > 3 and response[3:4] == b"-":
        response = file.readline()
    return response

def smtp_dialog(lines):
    with socket.create_connection(("127.0.0.1", 2525), timeout=5) as sock:
        file = sock.makefile("rwb", buffering=0)
        assert file.readline().startswith(b"220")
        responses = []
        for line in lines:
            file.write((line + "\r\n").encode())
            responses.append(read_smtp_response(file).decode())
        return responses

def smtp_data(sender, recipient, raw_message, authenticated=False):
    with socket.create_connection(("127.0.0.1", 2525), timeout=5) as sock:
        file = sock.makefile("rwb", buffering=0)
        assert file.readline().startswith(b"220")
        file.write(b"EHLO ci.example\r\n")
        assert read_smtp_response(file).startswith(b"250")
        if authenticated:
            token = base64.b64encode(("\0" + ADDRESS + "\0" + PASSWORD).encode())
            file.write(b"AUTH PLAIN " + token + b"\r\n")
            assert read_smtp_response(file).startswith(b"235")
        file.write(("MAIL FROM:<" + sender + ">\r\n").encode())
        assert read_smtp_response(file).startswith(b"250")
        file.write(("RCPT TO:<" + recipient + ">\r\n").encode())
        assert read_smtp_response(file).startswith(b"250")
        file.write(b"DATA\r\n")
        assert read_smtp_response(file).startswith(b"354")
        if not raw_message.endswith(b"\r\n"):
            raw_message += b"\r\n"
        file.write(raw_message + b".\r\n")
        assert read_smtp_response(file).startswith(b"250")
        file.write(b"QUIT\r\n")
        assert read_smtp_response(file).startswith(b"221")

def imap_fetch():
    with socket.create_connection(("127.0.0.1", 2143), timeout=5) as sock:
        file = sock.makefile("rwb", buffering=0)
        assert file.readline().startswith(b"* OK")
        file.write(('a1 LOGIN "' + ADDRESS + '" "' + PASSWORD + '"\r\n').encode())
        assert file.readline().startswith(b"a1 OK")
        file.write(b"a2 SELECT INBOX\r\n")
        select = b""
        while b"a2 OK" not in select:
            chunk = file.readline()
            assert chunk
            select += chunk
        assert b"EXISTS" in select
        file.write(b"a3 FETCH 1:* (UID BODY[])\r\n")
        fetched = b""
        while b"a3 OK" not in fetched:
            chunk = file.readline()
            assert chunk
            fetched += chunk
        file.write(b"a4 LOGOUT\r\n")
        assert file.readline().startswith(b"* BYE")
        assert file.readline().startswith(b"a4 OK")
        return fetched

wait_health()
status, _ = call("PUT", "/v1/domains/" + DOMAIN)
assert status == 204

try:
    status, _ = call("PUT", "/v1/mailboxes/" + ADDRESS, {
        "displayName": "Alice Test",
        "password": PASSWORD,
        "quotaBytes": 10 * 1024 * 1024
    })
    assert status == 204
except urllib.error.HTTPError as exc:
    assert exc.code == 400

status, auth = call("POST", "/v1/auth", {"address": ADDRESS, "password": PASSWORD})
assert status == 200 and auth["authenticated"] is True

boundary = "tuku-v2-proof"
attachment = base64.b64encode(b"TUKU-ENGINE-V2-ATTACHMENT-PROOF").decode()
multipart = (
    "From: sender@outside.test\r\n"
    f"To: {ADDRESS}\r\n"
    "Subject: Engine v2 MIME attachment smoke\r\n"
    "MIME-Version: 1.0\r\n"
    f'Content-Type: multipart/mixed; boundary="{boundary}"\r\n'
    "\r\n"
    f"--{boundary}\r\n"
    "Content-Type: text/plain; charset=utf-8\r\n\r\n"
    "hello from multipart smtp\r\n"
    f"--{boundary}\r\n"
    "Content-Type: application/octet-stream\r\n"
    'Content-Disposition: attachment; filename="proof.bin"\r\n'
    "Content-Transfer-Encoding: base64\r\n\r\n"
    f"{attachment}\r\n"
    f"--{boundary}--\r\n"
).encode()
smtp_data("sender@outside.test", ADDRESS, multipart)

status, messages = call("GET", "/v1/mailboxes/" + ADDRESS + "/messages?limit=20")
assert status == 200
hit = next(m for m in messages if m["subject"] == "Engine v2 MIME attachment smoke")
assert "hello from multipart smtp" in hit["preview"]

fetched = imap_fetch()
assert b"proof.bin" in fetched
assert attachment.encode() in fetched
assert b"Engine v2 MIME attachment smoke" in fetched

responses = smtp_dialog([
    "EHLO ci.example",
    "MAIL FROM:<sender@outside.test>",
    "RCPT TO:<recipient@outside.test>",
    "QUIT",
])
assert responses[2].startswith("550"), responses

remote_raw = (
    f"From: {ADDRESS}\r\n"
    "To: recipient@outside.test\r\n"
    "Subject: Engine v2 queued MIME smoke\r\n"
    "MIME-Version: 1.0\r\n"
    "Content-Type: text/plain; charset=utf-8\r\n\r\n"
    "this message must persist in the outbound queue\r\n"
).encode()
smtp_data(ADDRESS, "recipient@outside.test", remote_raw, authenticated=True)

raw_api = (
    f"From: {ADDRESS}\r\n"
    "To: raw-recipient@outside.test\r\n"
    "Subject: Engine v2 raw API smoke\r\n"
    "MIME-Version: 1.0\r\n"
    "Content-Type: application/octet-stream\r\n"
    "Content-Transfer-Encoding: base64\r\n\r\n"
    + attachment + "\r\n"
).encode()
status, _ = call("POST", "/v1/mailboxes/" + ADDRESS + "/send-raw", {
    "recipients": ["raw-recipient@outside.test"],
    "rawBase64": base64.b64encode(raw_api).decode(),
})
assert status == 202

print("Tuku Engine v2 integration smoke: PASS")
