from http.server import HTTPServer, BaseHTTPRequestHandler
import random, time
fail_rate = 0.6
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{"status":"UP"}')
            return
        if random.random() < fail_rate:
            time.sleep(3)
            self.send_response(500)
            self.end_headers()
            self.wfile.write(b'{"error":"Internal Server Error"}')
        else:
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{"status":"ok","data":"response from downstream"}')
HTTPServer(('0.0.0.0', 8080), Handler).serve_forever()
