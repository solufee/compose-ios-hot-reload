import socket, struct, sys
path = sys.argv[1].encode()
s = socket.create_connection(("127.0.0.1", 5567), timeout=5)
s.sendall(struct.pack("<I", 1) + struct.pack("<I", len(path)) + path)
s.close()
print("sent", sys.argv[1])
