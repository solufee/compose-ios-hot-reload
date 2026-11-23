"""Fake GitHub Pages Maven: serves com/solufee/cmp/... from a local Maven dir, answers 503 for every other path (as Pages
sometimes does for paths it does not have). Usage: fake503.py MAVEN_DIR PORT LOG"""
import http.server, os, sys
root, port, log = sys.argv[1], int(sys.argv[2]), open(sys.argv[3], "a", buffering=1)
class H(http.server.BaseHTTPRequestHandler):
    def _go(self, body):
        p = self.path.split("?")[0].lstrip("/")
        f = os.path.join(root, p)
        if p.startswith("com/solufee/cmp/") and os.path.isfile(f):
            data = open(f, "rb").read(); code = 200
        elif p.startswith("com/solufee/cmp/"):
            data = b""; code = 404
        else:
            data = b"Service Unavailable"; code = 503
        log.write(f"{code} {self.command} /{p}\n")
        self.send_response(code); self.send_header("Content-Length", str(len(data))); self.end_headers()
        if body: self.wfile.write(data)
    def do_GET(self): self._go(True)
    def do_HEAD(self): self._go(False)
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(("127.0.0.1", port), H).serve_forever()
