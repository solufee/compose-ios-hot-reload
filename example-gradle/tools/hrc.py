#!/usr/bin/env python3
"""Send one compiler invocation to the hot-reload compile daemon.

Usage: hrc.py <socket> <arg> [arg...]
Exit code: 0 on OK, 1 on compiler error, 2 when the daemon is unreachable.
Prints the daemon's response line ("OK <ms>") on success.
"""
import socket
import sys

sock_path = sys.argv[1]
args = sys.argv[2:]
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
try:
    s.connect(sock_path)
except OSError as e:
    print(f"daemon not reachable: {e}", file=sys.stderr)
    sys.exit(2)
payload = "\n".join(args) + "\n\n"
s.sendall(payload.encode())
buf = b""
while not buf.endswith(b"\n"):
    chunk = s.recv(4096)
    if not chunk:
        break
    buf += chunk
s.close()
line = buf.decode(errors="replace").strip()
print(line)
sys.exit(0 if line.startswith("OK") else 1)
