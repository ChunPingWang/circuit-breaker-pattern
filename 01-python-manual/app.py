from http.server import HTTPServer, BaseHTTPRequestHandler
import urllib.request
import json
import os

from circuit_breaker import CircuitBreaker

cb = CircuitBreaker(failure_threshold=3, recovery_timeout=15, half_open_max=2)
DOWNSTREAM_URL = os.environ.get('DOWNSTREAM_URL', 'http://localhost:8080')

def call_downstream():
    req = urllib.request.urlopen(DOWNSTREAM_URL, timeout=2)
    if req.status != 200:
        raise Exception(f"HTTP {req.status}")
    return req.read().decode()

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        result, error = cb.call(call_downstream)
        response = {
            "circuit_state": cb.state,
            "failure_count": cb.failure_count,
        }
        if error:
            response["error"] = error
            response["fallback"] = "cached/default response"
            self.send_response(503)
        else:
            response["downstream_response"] = json.loads(result)
            self.send_response(200)

        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps(response, indent=2).encode())

print("[CB App] Starting with Circuit Breaker (threshold=3, recovery=15s)")
HTTPServer(('0.0.0.0', 8080), Handler).serve_forever()
