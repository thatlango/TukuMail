#!/usr/bin/env python3
"""Read-only SMTP infrastructure preflight. Never submits a message."""
import argparse
import json
import socket
import sys
import ipaddress

def inspect(hostname, public_ip, mx_hosts, timeout=5):
    checks = []
    def check(name, fn):
        try:
            ok, detail = fn()
        except (OSError, ValueError) as exc:
            ok, detail = False, str(exc)
        checks.append({"check": name, "pass": bool(ok), "detail": detail})

    def forward():
        addresses = {r[4][0] for r in socket.getaddrinfo(hostname, None, socket.AF_INET)}
        return public_ip in addresses, sorted(addresses)

    def reverse():
        ptr = socket.gethostbyaddr(public_ip)[0].rstrip(".").lower()
        return ptr == hostname.rstrip(".").lower(), ptr

    def smtp_banner(mx):
        # Connect to port 25 and read the server greeting only. No MAIL/RCPT/DATA.
        with socket.create_connection((mx, 25), timeout=timeout) as conn:
            conn.settimeout(timeout)
            with conn.makefile("rb") as stream:
                line = stream.readline(1024)
            return line.startswith(b"220 "), line.decode("ascii", errors="replace").strip()

    check("forward_dns", forward)
    check("reverse_dns", reverse)
    for mx in mx_hosts:
        check("smtp_25:" + mx, lambda mx=mx: smtp_banner(mx))
    return checks

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hostname", required=True)
    parser.add_argument("--public-ip", required=True)
    parser.add_argument("--mx", action="append", required=True, help="Known test recipient MX; repeat for multiple providers")
    parser.add_argument("--timeout", type=float, default=5)
    args = parser.parse_args()
    try:
        ip = ipaddress.IPv4Address(args.public_ip)
        if not ip.is_global:
            raise ValueError("expected globally routable IPv4")
        if not 0 < args.timeout <= 15:
            raise ValueError("timeout must be greater than 0 and at most 15 seconds")
    except ValueError as exc:
        parser.error(str(exc))
    checks = inspect(args.hostname, str(ip), args.mx, args.timeout)
    print(json.dumps({"checks": checks, "passed": all(c["pass"] for c in checks)}, indent=2))
    return 0 if all(c["pass"] for c in checks) else 1

if __name__ == "__main__":
    sys.exit(main())
