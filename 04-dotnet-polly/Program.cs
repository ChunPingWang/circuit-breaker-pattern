using CircuitBreakerDemo.Services;
using CircuitBreakerDemo.Middleware;
using Microsoft.Extensions.Http.Resilience;
using Polly;
using Polly.CircuitBreaker;
using Polly.Timeout;

var builder = WebApplication.CreateBuilder(args);

var downstreamUrl = builder.Configuration["DownstreamUrl"] ?? "http://localhost:8080";

// ==============================================
//  方式 1: Microsoft.Extensions.Http.Resilience
//  (標準 Resilience Handler — 推薦做法)
// ==============================================
builder.Services
    .AddHttpClient("DownstreamStandard", client =>
    {
        client.BaseAddress = new Uri(downstreamUrl);
        client.Timeout = TimeSpan.FromSeconds(5);
    })
    .AddStandardResilienceHandler(options =>
    {
        // Circuit Breaker 設定
        options.CircuitBreaker.SamplingDuration = TimeSpan.FromSeconds(30);
        options.CircuitBreaker.FailureRatio = 0.5;
        options.CircuitBreaker.MinimumThroughput = 3;
        options.CircuitBreaker.BreakDuration = TimeSpan.FromSeconds(15);
        options.CircuitBreaker.ShouldHandle = args => ValueTask.FromResult(
            args.Outcome.Result?.IsSuccessStatusCode == false
            || args.Outcome.Exception is not null
        );

        // Timeout (per attempt)
        options.AttemptTimeout.Timeout = TimeSpan.FromSeconds(2);

        // Total Timeout
        options.TotalRequestTimeout.Timeout = TimeSpan.FromSeconds(10);

        // Retry
        options.Retry.MaxRetryAttempts = 2;
        options.Retry.Delay = TimeSpan.FromMilliseconds(500);
        options.Retry.BackoffType = DelayBackoffType.Exponential;
        options.Retry.ShouldHandle = args => ValueTask.FromResult(
            args.Outcome.Result?.IsSuccessStatusCode == false
            || args.Outcome.Exception is not null
        );
    });

// ==============================================
//  方式 2: 自訂 Polly v8 Pipeline (精細控制)
// ==============================================
builder.Services
    .AddHttpClient("DownstreamCustom", client =>
    {
        client.BaseAddress = new Uri(downstreamUrl);
        client.Timeout = TimeSpan.FromSeconds(5);
    })
    .AddResilienceHandler("custom-pipeline", pipelineBuilder =>
    {
        // Circuit Breaker
        pipelineBuilder.AddCircuitBreaker(new HttpCircuitBreakerStrategyOptions
        {
            Name = "custom-circuit-breaker",
            SamplingDuration = TimeSpan.FromSeconds(20),
            FailureRatio = 0.4,
            MinimumThroughput = 2,
            BreakDuration = TimeSpan.FromSeconds(30),
            ShouldHandle = args => ValueTask.FromResult(
                args.Outcome.Result?.IsSuccessStatusCode == false
                || args.Outcome.Exception is not null
            ),
            OnOpened = args =>
            {
                Console.WriteLine($"[Custom CB] OPENED! Break duration: {args.BreakDuration}");
                return ValueTask.CompletedTask;
            },
            OnClosed = args =>
            {
                Console.WriteLine("[Custom CB] CLOSED - Circuit recovered");
                return ValueTask.CompletedTask;
            },
            OnHalfOpened = args =>
            {
                Console.WriteLine("[Custom CB] HALF-OPEN - Testing recovery...");
                return ValueTask.CompletedTask;
            }
        });

        // Timeout
        pipelineBuilder.AddTimeout(new HttpTimeoutStrategyOptions
        {
            Name = "custom-timeout",
            Timeout = TimeSpan.FromSeconds(2)
        });
    });

// ==============================================
//  方式 3: Polly v8 ResiliencePipeline (非 HTTP)
//  適用於任意操作（DB、gRPC、MQ 等）
// ==============================================
builder.Services.AddResiliencePipeline("generic-pipeline", pipelineBuilder =>
{
    pipelineBuilder
        .AddCircuitBreaker(new CircuitBreakerStrategyOptions
        {
            Name = "generic-circuit-breaker",
            SamplingDuration = TimeSpan.FromSeconds(30),
            FailureRatio = 0.5,
            MinimumThroughput = 3,
            BreakDuration = TimeSpan.FromSeconds(15),
            ShouldHandle = new PredicateBuilder().Handle<Exception>(),
            OnOpened = args =>
            {
                Console.WriteLine($"[Generic CB] OPENED!");
                return ValueTask.CompletedTask;
            },
            OnClosed = args =>
            {
                Console.WriteLine("[Generic CB] CLOSED");
                return ValueTask.CompletedTask;
            },
            OnHalfOpened = args =>
            {
                Console.WriteLine("[Generic CB] HALF-OPEN");
                return ValueTask.CompletedTask;
            }
        })
        .AddTimeout(TimeSpan.FromSeconds(2))
        .AddRetry(new Polly.Retry.RetryStrategyOptions
        {
            MaxRetryAttempts = 2,
            Delay = TimeSpan.FromMilliseconds(500),
            BackoffType = DelayBackoffType.Exponential,
            ShouldHandle = new PredicateBuilder().Handle<Exception>()
        });
});

// Services
builder.Services.AddSingleton<CircuitBreakerTracker>();
builder.Services.AddScoped<DownstreamService>();

// Health Checks
builder.Services.AddHealthChecks()
    .AddUrlGroup(new Uri($"{downstreamUrl}/health"), "downstream-service");

var app = builder.Build();

// ==============================================
//  API 端點
// ==============================================

// --- 方式 1: Standard Resilience Handler ---
app.MapGet("/api/call", async (DownstreamService svc) =>
{
    var result = await svc.CallWithStandardResilience();
    return Results.Ok(result);
});

// --- 方式 2: Custom Polly Pipeline ---
app.MapGet("/api/call-custom", async (DownstreamService svc) =>
{
    var result = await svc.CallWithCustomPipeline();
    return Results.Ok(result);
});

// --- 方式 3: Generic Pipeline (非 HTTP) ---
app.MapGet("/api/call-generic", async (DownstreamService svc) =>
{
    var result = await svc.CallWithGenericPipeline();
    return Results.Ok(result);
});

// --- Dashboard ---
app.MapGet("/api/dashboard", (CircuitBreakerTracker tracker) =>
{
    return Results.Ok(tracker.GetDashboard());
});

// --- Health Check ---
app.MapHealthChecks("/health");

Console.WriteLine("============================================");
Console.WriteLine("  .NET 8 Circuit Breaker PoC Started");
Console.WriteLine("  Polly v8 + Microsoft.Extensions.Http.Resilience");
Console.WriteLine("============================================");

app.Run();

public partial class Program { }
