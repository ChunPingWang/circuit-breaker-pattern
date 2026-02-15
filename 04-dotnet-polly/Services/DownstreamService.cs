using Polly;
using Polly.Registry;

namespace CircuitBreakerDemo.Services;

public class DownstreamService
{
    private readonly IHttpClientFactory _httpClientFactory;
    private readonly ResiliencePipelineProvider<string> _pipelineProvider;
    private readonly CircuitBreakerTracker _tracker;
    private readonly ILogger<DownstreamService> _logger;

    public DownstreamService(
        IHttpClientFactory httpClientFactory,
        ResiliencePipelineProvider<string> pipelineProvider,
        CircuitBreakerTracker tracker,
        ILogger<DownstreamService> logger)
    {
        _httpClientFactory = httpClientFactory;
        _pipelineProvider = pipelineProvider;
        _tracker = tracker;
        _logger = logger;
    }

    /// <summary>
    /// 方式 1: Standard Resilience Handler
    /// HttpClient 已內建 Circuit Breaker + Retry + Timeout
    /// </summary>
    public async Task<object> CallWithStandardResilience()
    {
        var client = _httpClientFactory.CreateClient("DownstreamStandard");
        var startTime = DateTime.UtcNow;

        try
        {
            _logger.LogInformation("[Standard] Calling downstream...");
            var response = await client.GetAsync("/");
            var content = await response.Content.ReadAsStringAsync();
            var elapsed = (DateTime.UtcNow - startTime).TotalMilliseconds;

            _tracker.RecordCall("standard", true);
            _logger.LogInformation("[Standard] Success ({Elapsed}ms)", elapsed);

            return new
            {
                pipeline = "standard-resilience-handler",
                status = "SUCCESS",
                latency_ms = Math.Round(elapsed, 2),
                response = content,
                stats = _tracker.GetStats("standard")
            };
        }
        catch (Exception ex)
        {
            var elapsed = (DateTime.UtcNow - startTime).TotalMilliseconds;
            _tracker.RecordCall("standard", false);
            _logger.LogWarning("[Standard] FALLBACK! {Error}", ex.Message);

            var isBroken = ex is Polly.CircuitBreaker.BrokenCircuitException
                        || ex.InnerException is Polly.CircuitBreaker.BrokenCircuitException;

            return new
            {
                pipeline = "standard-resilience-handler",
                status = isBroken ? "CIRCUIT_OPEN" : "FALLBACK",
                latency_ms = Math.Round(elapsed, 2),
                error = ex.Message,
                fallback_response = "Default/cached response from .NET fallback",
                stats = _tracker.GetStats("standard")
            };
        }
    }

    /// <summary>
    /// 方式 2: Custom Polly v8 Pipeline (精細控制，含事件回呼)
    /// </summary>
    public async Task<object> CallWithCustomPipeline()
    {
        var client = _httpClientFactory.CreateClient("DownstreamCustom");
        var startTime = DateTime.UtcNow;

        try
        {
            _logger.LogInformation("[Custom] Calling downstream...");
            var response = await client.GetAsync("/");
            var content = await response.Content.ReadAsStringAsync();
            var elapsed = (DateTime.UtcNow - startTime).TotalMilliseconds;

            _tracker.RecordCall("custom", response.IsSuccessStatusCode);

            if (!response.IsSuccessStatusCode)
            {
                return new
                {
                    pipeline = "custom-polly-pipeline",
                    status = "DOWNSTREAM_ERROR",
                    http_code = (int)response.StatusCode,
                    latency_ms = Math.Round(elapsed, 2),
                    response = content,
                    stats = _tracker.GetStats("custom")
                };
            }

            return new
            {
                pipeline = "custom-polly-pipeline",
                status = "SUCCESS",
                latency_ms = Math.Round(elapsed, 2),
                response = content,
                stats = _tracker.GetStats("custom")
            };
        }
        catch (Exception ex)
        {
            var elapsed = (DateTime.UtcNow - startTime).TotalMilliseconds;
            _tracker.RecordCall("custom", false);

            var isBroken = ex is Polly.CircuitBreaker.BrokenCircuitException
                        || ex.InnerException is Polly.CircuitBreaker.BrokenCircuitException;

            return new
            {
                pipeline = "custom-polly-pipeline",
                status = isBroken ? "CIRCUIT_OPEN" : "FALLBACK",
                latency_ms = Math.Round(elapsed, 2),
                error = ex.Message,
                stats = _tracker.GetStats("custom")
            };
        }
    }

    /// <summary>
    /// 方式 3: Generic ResiliencePipeline (非 HTTP 場景)
    /// 可用於 DB 查詢、gRPC 呼叫、MQ 操作等
    /// </summary>
    public async Task<object> CallWithGenericPipeline()
    {
        var pipeline = _pipelineProvider.GetPipeline("generic-pipeline");
        var startTime = DateTime.UtcNow;

        try
        {
            var result = await pipeline.ExecuteAsync(async ct =>
            {
                _logger.LogInformation("[Generic] Executing operation...");

                // 模擬非 HTTP 操作 (例如 DB query)
                using var client = new HttpClient { BaseAddress = new Uri("http://flaky-service/") };
                client.Timeout = TimeSpan.FromSeconds(5);
                var response = await client.GetStringAsync("/", ct);
                return response;
            });

            var elapsed = (DateTime.UtcNow - startTime).TotalMilliseconds;
            _tracker.RecordCall("generic", true);

            return new
            {
                pipeline = "generic-resilience-pipeline",
                status = "SUCCESS",
                latency_ms = Math.Round(elapsed, 2),
                response = result,
                note = "適用於 DB / gRPC / MQ 等非 HTTP 場景",
                stats = _tracker.GetStats("generic")
            };
        }
        catch (Exception ex)
        {
            var elapsed = (DateTime.UtcNow - startTime).TotalMilliseconds;
            _tracker.RecordCall("generic", false);

            var isBroken = ex is Polly.CircuitBreaker.BrokenCircuitException;

            return new
            {
                pipeline = "generic-resilience-pipeline",
                status = isBroken ? "CIRCUIT_OPEN" : "FALLBACK",
                latency_ms = Math.Round(elapsed, 2),
                error = ex.Message,
                stats = _tracker.GetStats("generic")
            };
        }
    }
}
