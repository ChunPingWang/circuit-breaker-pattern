using System.Collections.Concurrent;

namespace CircuitBreakerDemo.Services;

/// <summary>
/// 輕量級 CB 統計追蹤器
/// 生產環境建議改用 OpenTelemetry Metrics
/// </summary>
public class CircuitBreakerTracker
{
    private readonly ConcurrentDictionary<string, PipelineStats> _stats = new();

    public void RecordCall(string pipeline, bool success)
    {
        var stats = _stats.GetOrAdd(pipeline, _ => new PipelineStats());
        Interlocked.Increment(ref stats.TotalCalls);
        if (success)
            Interlocked.Increment(ref stats.SuccessfulCalls);
        else
            Interlocked.Increment(ref stats.FailedCalls);
    }

    public object GetStats(string pipeline)
    {
        var stats = _stats.GetOrAdd(pipeline, _ => new PipelineStats());
        var total = stats.TotalCalls;
        var failRate = total > 0 ? Math.Round((double)stats.FailedCalls / total * 100, 2) : 0;

        return new
        {
            total_calls = stats.TotalCalls,
            successful = stats.SuccessfulCalls,
            failed = stats.FailedCalls,
            failure_rate_percent = failRate
        };
    }

    public object GetDashboard()
    {
        var dashboard = new Dictionary<string, object>();
        foreach (var kvp in _stats)
        {
            dashboard[kvp.Key] = GetStats(kvp.Key);
        }

        dashboard["_info"] = new
        {
            note = "Polly v8 使用 Metering API，生產環境可透過 OpenTelemetry 匯出至 Prometheus",
            pipelines = new
            {
                standard = "AddStandardResilienceHandler (一行搞定 CB + Retry + Timeout)",
                custom = "AddResilienceHandler (自訂 Pipeline，精細事件回呼)",
                generic = "AddResiliencePipeline (非 HTTP，適用 DB/gRPC/MQ)"
            }
        };

        return dashboard;
    }

    private class PipelineStats
    {
        public int TotalCalls;
        public int SuccessfulCalls;
        public int FailedCalls;
    }
}
