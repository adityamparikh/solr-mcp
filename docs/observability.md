# Observability

## Overview ##

When running in **HTTP mode**, the Solr MCP Server exports telemetry data via OpenTelemetry to the **LGTM stack** (Loki, Grafana, Tempo, Mimir) for full observability.

| Signal | Backend | What it shows |
|--------|---------|---------------|
| **Traces** | Tempo | A trace per HTTP request, with a span for the MCP tool it invoked |
| **Metrics** | Mimir/Prometheus | HTTP request rate and latency, per-tool latency, JVM, Tomcat and Spring Security metrics |
| **Logs** | Loki | Application logs, each tagged with the trace and span it was written under |

Every MCP tool invocation creates a span named after the service class and method, such as
`SearchService#search` or `CollectionService#checkHealth`, inside the trace of the HTTP
request that carried it. The call to Solr is not a separate span; its time is part of the
tool span.

***

## Setup ##

### Start the LGTM Stack ###

The project's `compose.yaml` includes a Grafana OTEL LGTM all-in-one container:

```bash
docker compose up -d
```

This starts:

| Service | URL | Purpose |
|---------|-----|---------|
| Grafana | http://localhost:3000 | Dashboards and exploration (no auth required) |
| OTLP HTTP | localhost:4318 | Trace/metric/log ingestion — **the port this server exports to** |
| OTLP gRPC | localhost:4317 | Also accepted by the collector; not used by this server |

### Run the Server with Observability ###

```bash
PROFILES=http ./gradlew bootRun
```

The server auto-configures OTLP export when the LGTM stack is running. Default configuration:

```properties
management.tracing.sampling.probability=1.0     # 100% sampling (dev)
management.opentelemetry.tracing.export.otlp.endpoint=${OTEL_TRACES_URL:http://localhost:4318/v1/traces}
management.otlp.metrics.export.url=${OTEL_METRICS_URL:http://localhost:4318/v1/metrics}
management.opentelemetry.logging.export.otlp.endpoint=${OTEL_LOGS_URL:http://localhost:4318/v1/logs}
```

Export goes over **OTLP/HTTP on port 4318**, with a separate full URL per signal.
Each endpoint is a complete path ending in `/v1/traces`, `/v1/metrics` or
`/v1/logs` — not a base address.

***

## Grafana ##

Open [http://localhost:3000](http://localhost:3000) and click **Explore** in the left sidebar.

### View Traces (Tempo) ###

1. Select **Tempo** as the data source
2. Use TraceQL to search:

        {.service.name="solr-mcp"}

3. Click on an `http post /mcp` trace to see the span waterfall: the security filter
   chain, then one span for the tool (`SearchService#search`,
   `CollectionService#checkHealth`, &hellip;) with its duration. Each span's **Logs for
   this span** link opens the lines that request logged in Loki.

### View Logs (Loki) ###

1. Select **Loki** as the data source
2. Use LogQL to search:

        {service_name="solr-mcp"} | trace_id != ""

   That keeps only lines written during a request; drop the filter to include startup
   and lifecycle lines, which have no trace.
3. Expand a line and follow its **Trace** link to open the request in Tempo.

Every tool call logs one line when it finishes, written under the request's trace, so a
tool call's **Logs for this span** link always finds at least that line:

```text
INFO  ... o.a.s.m.s.o.ToolCallLoggingHandler : SearchService#search completed in 43 ms
WARN  ... o.a.s.m.s.o.ToolCallLoggingHandler : SearchService#search failed after 9 ms: java.lang.IllegalArgumentException
```

Any warning the tool logs itself, such as `check-health` on a missing collection, appears
alongside it. A request that runs no tool, such as `tools/list`, logs nothing, so its link
is empty.

### View Metrics (Prometheus) ###

1. Select **Prometheus** as the data source
2. Example queries:

        # MCP request rate, by method and status
        sum by (method, status) (rate(http_server_requests_milliseconds_count{job="solr-mcp", uri="/mcp"}[5m]))

        # Average MCP request latency (ms)
        sum(rate(http_server_requests_milliseconds_sum{job="solr-mcp", uri="/mcp"}[5m]))
          / sum(rate(http_server_requests_milliseconds_count{job="solr-mcp", uri="/mcp"}[5m]))

        # Average latency per tool (ms)
        sum by (class, method) (rate(method_observed_milliseconds_sum{job="solr-mcp"}[5m]))
          / sum by (class, method) (rate(method_observed_milliseconds_count{job="solr-mcp"}[5m]))

        # JVM memory, heap vs non-heap
        sum by (area) (jvm_memory_used_bytes{job="solr-mcp"})

   Timers are exported over OTLP in **milliseconds**, so their names end in
   `_milliseconds_*`; queries written for `http_server_requests_seconds_*` return nothing.
   Metrics are exported once a minute, and a `rate()` needs two exports, so allow two
   minutes after the first calls. A tool with no calls in the window shows `NaN`.

   Timers publish only the `+Inf` bucket by default, so percentiles return `NaN`. Enable
   buckets for HTTP requests with
   `management.metrics.distribution.percentiles-histogram.http.server.requests=true`,
   then:

        # MCP request latency, p99 (ms)
        histogram_quantile(0.99, sum by (le) (rate(http_server_requests_milliseconds_bucket{job="solr-mcp", uri="/mcp"}[5m])))

***

## Actuator Endpoints ##

The following health and metrics endpoints are exposed in HTTP mode:

```bash
curl http://localhost:8080/actuator/health       # Health check
curl http://localhost:8080/actuator/info          # Build info
curl http://localhost:8080/actuator/metrics       # Available metrics
curl http://localhost:8080/actuator/loggers       # Logger levels
```

***

## Production Configuration ##

For production, reduce the sampling rate and point each signal at your collector:

```bash
export OTEL_SAMPLING_PROBABILITY=0.1                                          # 10% sampling
export OTEL_TRACES_URL=https://otel-collector.example.com/v1/traces
export OTEL_METRICS_URL=https://otel-collector.example.com/v1/metrics
export OTEL_LOGS_URL=https://otel-collector.example.com/v1/logs
PROFILES=http java -jar build/libs/solr-mcp-1.0.0-SNAPSHOT.jar
```

| Variable | Default | Purpose |
|----------|---------|---------|
| `OTEL_SAMPLING_PROBABILITY` | `1.0` | Fraction of traces sampled |
| `OTEL_TRACES_URL` | `http://localhost:4318/v1/traces` | OTLP/HTTP traces endpoint |
| `OTEL_METRICS_URL` | `http://localhost:4318/v1/metrics` | OTLP/HTTP metrics endpoint |
| `OTEL_LOGS_URL` | `http://localhost:4318/v1/logs` | OTLP/HTTP logs endpoint |

> **Upgrading from a pre-Spring-Boot-4 release?** `OTEL_TRACES_URL` changed meaning.
> It used to be a *base* endpoint on the gRPC port (`http://collector:4317`); it is now
> the *complete* traces URL on the HTTP port (`http://collector:4318/v1/traces`). A value
> carried over unchanged will not error — traces simply stop arriving. `OTEL_METRICS_URL`
> and `OTEL_LOGS_URL` are new; previously all three signals shared one endpoint.

For the exporter architecture and how the Logback OTLP appender is wired, see
[dev-docs/Observability.md](../dev-docs/Observability.md).
