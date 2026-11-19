import os, socket, struct, sys

port = int(os.environ.get("KONAN_HOT_RELOAD_PORT", "5567"))
path = sys.argv[1].encode()
s = socket.create_connection(("127.0.0.1", port), timeout=5)
s.sendall(struct.pack("<I", 1) + struct.pack("<I", len(path)) + path)
s.close()
print("sent", sys.argv[1], "port", port)
