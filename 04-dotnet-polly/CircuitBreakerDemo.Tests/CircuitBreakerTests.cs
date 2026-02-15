using System.Net;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using WireMock.RequestBuilders;
using WireMock.ResponseBuilders;
using WireMock.Server;
using Xunit;

namespace CircuitBreakerDemo.Tests;

public class CircuitBreakerTests : IAsyncLifetime
{
    private WireMockServer _wireMock = null!;
    private WebApplicationFactory<Program> _factory = null!;
    private HttpClient _client = null!;

    public Task InitializeAsync()
    {
        _wireMock = WireMockServer.Start();

        _factory = new WebApplicationFactory<Program>()
            .WithWebHostBuilder(builder =>
            {
                builder.UseSetting("DownstreamUrl", _wireMock.Url!);
            });

        _client = _factory.CreateClient();
        return Task.CompletedTask;
    }

    public async Task DisposeAsync()
    {
        _client.Dispose();
        await _factory.DisposeAsync();
        _wireMock.Stop();
    }

    [Fact]
    public async Task StandardPipeline_Success_ReturnsOk()
    {
        _wireMock.Given(Request.Create().WithPath("/").UsingGet())
            .RespondWith(Response.Create()
                .WithStatusCode(200)
                .WithHeader("Content-Type", "application/json")
                .WithBody("{\"status\":\"ok\"}"));

        var response = await _client.GetAsync("/api/call");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        var body = await response.Content.ReadAsStringAsync();
        Assert.Contains("SUCCESS", body);
        Assert.Contains("standard-resilience-handler", body);
    }

    [Fact]
    public async Task CustomPipeline_Success_ReturnsOk()
    {
        _wireMock.Given(Request.Create().WithPath("/").UsingGet())
            .RespondWith(Response.Create()
                .WithStatusCode(200)
                .WithHeader("Content-Type", "application/json")
                .WithBody("{\"status\":\"ok\"}"));

        var response = await _client.GetAsync("/api/call-custom");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        var body = await response.Content.ReadAsStringAsync();
        Assert.Contains("custom-polly-pipeline", body);
    }

    [Fact]
    public async Task Dashboard_ReturnsOk()
    {
        var response = await _client.GetAsync("/api/dashboard");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task StandardPipeline_DownstreamFailure_ReturnsFallback()
    {
        _wireMock.Given(Request.Create().WithPath("/").UsingGet())
            .RespondWith(Response.Create()
                .WithStatusCode(500)
                .WithBody("Internal Server Error"));

        // Make several calls to trigger failures
        for (int i = 0; i < 5; i++)
        {
            var response = await _client.GetAsync("/api/call");
            Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        }

        // Verify the response contains fallback info
        var lastResponse = await _client.GetAsync("/api/call");
        var body = await lastResponse.Content.ReadAsStringAsync();
        Assert.Contains("standard-resilience-handler", body);
    }
}
