import time
from circuit_breaker import CircuitBreaker


class TestCircuitBreakerStateTransitions:
    """Test CLOSED -> OPEN -> HALF_OPEN -> CLOSED state transitions."""

    def test_initial_state_is_closed(self):
        cb = CircuitBreaker()
        assert cb.state == CircuitBreaker.CLOSED

    def test_stays_closed_on_success(self):
        cb = CircuitBreaker(failure_threshold=3)
        result, error = cb.call(lambda: "ok")
        assert result == "ok"
        assert error is None
        assert cb.state == CircuitBreaker.CLOSED

    def test_stays_closed_below_threshold(self):
        cb = CircuitBreaker(failure_threshold=3)
        for _ in range(2):
            cb.call(lambda: (_ for _ in ()).throw(Exception("fail")))
        assert cb.state == CircuitBreaker.CLOSED
        assert cb.failure_count == 2

    def test_opens_at_failure_threshold(self):
        cb = CircuitBreaker(failure_threshold=3)
        for _ in range(3):
            cb.call(lambda: (_ for _ in ()).throw(Exception("fail")))
        assert cb.state == CircuitBreaker.OPEN
        assert cb.failure_count == 3

    def test_open_rejects_requests(self):
        cb = CircuitBreaker(failure_threshold=1, recovery_timeout=60)
        cb.call(lambda: (_ for _ in ()).throw(Exception("fail")))
        assert cb.state == CircuitBreaker.OPEN

        result, error = cb.call(lambda: "should not run")
        assert result is None
        assert "OPEN" in error

    def test_open_to_half_open_after_recovery_timeout(self):
        cb = CircuitBreaker(failure_threshold=1, recovery_timeout=0.1)
        cb.call(lambda: (_ for _ in ()).throw(Exception("fail")))
        assert cb.state == CircuitBreaker.OPEN

        time.sleep(0.15)
        result, error = cb.call(lambda: "recovered")
        assert cb.state in (CircuitBreaker.HALF_OPEN, CircuitBreaker.CLOSED)
        assert result == "recovered"
        assert error is None

    def test_half_open_to_closed_after_successes(self):
        cb = CircuitBreaker(failure_threshold=1, recovery_timeout=0.1, half_open_max=2)
        cb.call(lambda: (_ for _ in ()).throw(Exception("fail")))
        assert cb.state == CircuitBreaker.OPEN

        time.sleep(0.15)

        # First success in HALF_OPEN
        result1, _ = cb.call(lambda: "ok1")
        assert result1 == "ok1"
        assert cb.state == CircuitBreaker.HALF_OPEN

        # Second success triggers CLOSED
        result2, _ = cb.call(lambda: "ok2")
        assert result2 == "ok2"
        assert cb.state == CircuitBreaker.CLOSED

    def test_half_open_to_open_on_failure(self):
        cb = CircuitBreaker(failure_threshold=1, recovery_timeout=0.1, half_open_max=2)
        cb.call(lambda: (_ for _ in ()).throw(Exception("fail")))
        assert cb.state == CircuitBreaker.OPEN

        time.sleep(0.15)

        # First call transitions to HALF_OPEN, but fails -> back to OPEN
        result, error = cb.call(lambda: (_ for _ in ()).throw(Exception("fail again")))
        assert result is None
        assert cb.state == CircuitBreaker.OPEN

    def test_success_resets_failure_count_in_closed(self):
        cb = CircuitBreaker(failure_threshold=3)
        cb.call(lambda: (_ for _ in ()).throw(Exception("fail")))
        cb.call(lambda: (_ for _ in ()).throw(Exception("fail")))
        assert cb.failure_count == 2

        cb.call(lambda: "ok")
        assert cb.failure_count == 0

    def test_full_cycle(self):
        """CLOSED -> OPEN -> HALF_OPEN -> CLOSED full cycle."""
        cb = CircuitBreaker(failure_threshold=2, recovery_timeout=0.1, half_open_max=1)

        # CLOSED: two failures -> OPEN
        cb.call(lambda: (_ for _ in ()).throw(Exception("f1")))
        cb.call(lambda: (_ for _ in ()).throw(Exception("f2")))
        assert cb.state == CircuitBreaker.OPEN

        # Wait for recovery
        time.sleep(0.15)

        # HALF_OPEN -> CLOSED (half_open_max=1, so one success is enough)
        result, error = cb.call(lambda: "recovered!")
        assert result == "recovered!"
        assert error is None
        assert cb.state == CircuitBreaker.CLOSED
        assert cb.failure_count == 0
