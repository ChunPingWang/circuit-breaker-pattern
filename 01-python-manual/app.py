from http.server import HTTPServer, BaseHTTPRequestHandler
import urllib.request
import json
import time
import threading

class CircuitBreaker:
    CLOSED = "CLOSED"
    OPEN = "OPEN"
    HALF_OPEN = "HALF_OPEN"

    def __init__(self, failure_threshold=3, recovery_timeout=10, half_open_max=2):
        self.state = self.CLOSED
        self.failure_count = 0
        self.success_count = 0
        self.failure_threshold = failure_threshold
        self.recovery_timeout = recovery_timeout
        self.half_open_max = half_open_max
        self.last_failure_time = None
        self.lock = threading.Lock()

    def call(self, func):
        with self.lock:
            if self.state == self.OPEN:
                if time.time() - self.last_failure_time > self.recovery_timeout:
                    print(f"[CB] OPEN -> HALF_OPEN (嘗試恢復)")
                    self.state = self.HALF_OPEN
                    self.success_count = 0
                else:
                    remaining = self.recovery_timeout - (time.time() - self.last_failure_time)
                    print(f"[CB] OPEN - 拒絕請求 (剩餘 {remaining:.1f}s)")
                    return None, "Circuit is OPEN - request rejected"

        try:
            result = func()
            with self.lock:
                if self.state == self.HALF_OPEN:
                    self.success_count += 1
                    print(f"[CB] HALF_OPEN 成功 ({self.success_count}/{self.half_open_max})")
                    if self.success_count >= self.half_open_max:
                        print(f"[CB] HALF_OPEN -> CLOSED (恢復正常)")
                        self.state = self.CLOSED
                        self.failure_count = 0
                elif self.state == self.CLOSED:
                    self.failure_count = 0
            return result, None
        except Exception as e:
            with self.lock:
                self.failure_count += 1
                self.last_failure_time = time.time()
                print(f"[CB] 失敗 ({self.failure_count}/{self.failure_threshold}) - {e}")
                if self.failure_count >= self.failure_threshold:
                    print(f"[CB] {self.state} -> OPEN (達到失敗閾值)")
                    self.state = self.OPEN
            return None, str(e)

cb = CircuitBreaker(failure_threshold=3, recovery_timeout=15, half_open_max=2)

def call_downstream():
    req = urllib.request.urlopen("http://flaky-service/", timeout=2)
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
