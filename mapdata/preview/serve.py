#!/usr/bin/env python3
"""Serve mapdata/ over HTTP with byte ranges, for preview/index.html.

PMTiles is read by range requests, which http.server does not do. Dev tool
only - nothing here ships in the app.

    python3 preview/serve.py [--port 8765] [--root mapdata/]
    then open http://127.0.0.1:8765/preview/?theme=night
"""
import argparse
import http.server
import os
import re
import socketserver

RANGE = re.compile(r"bytes=(\d*)-(\d*)$")


class Handler(http.server.SimpleHTTPRequestHandler):
    def send_head(self):
        self._remaining = None
        path = self.translate_path(self.path.split("?", 1)[0])
        m = RANGE.match(self.headers.get("Range", ""))
        if not m or not os.path.isfile(path):
            return super().send_head()
        size = os.path.getsize(path)
        a, b = m.groups()
        start = int(a) if a else max(0, size - int(b))
        end = min(int(b), size - 1) if a and b else size - 1
        if start > end or start >= size:
            self.send_error(416)
            return None
        f = open(path, "rb")
        f.seek(start)
        self.send_response(206)
        self.send_header("Content-Type", self.guess_type(path))
        self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        self.end_headers()
        self._remaining = end - start + 1
        return f

    def copyfile(self, source, outputfile):
        n = self._remaining
        if n is None:
            return super().copyfile(source, outputfile)
        while n > 0:
            chunk = source.read(min(65536, n))
            if not chunk:
                break
            outputfile.write(chunk)
            n -= len(chunk)

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def log_message(self, *args):
        pass

    def handle(self):
        # A browser dropping a tile request mid-pan is not an error (spec 01).
        try:
            super().handle()
        except (ConnectionResetError, BrokenPipeError):
            pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    a = ap.parse_args()
    os.chdir(a.root)
    socketserver.ThreadingTCPServer.allow_reuse_address = True
    socketserver.ThreadingTCPServer.daemon_threads = True
    with socketserver.ThreadingTCPServer(("127.0.0.1", a.port), Handler) as s:
        print(f"serving {a.root} on http://127.0.0.1:{a.port}/preview/", flush=True)
        s.serve_forever()


if __name__ == "__main__":
    main()
