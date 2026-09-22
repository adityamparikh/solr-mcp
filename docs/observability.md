# Observability

## Overview ##

When running in **HTTP mode**, the Solr MCP Server exports telemetry data via OpenTelemetry to the **LGTM stack** (Loki, Grafana, Tempo, Mimir) for full observability.

| Signal | Backend | What it shows |
|--------|---------|---------------|
| **Traces** | Tempo | Distributed traces for every MCP tool invocation, Solr query, and HTTP request |
| **Metrics** | Mimir/Prometheus | JVM stats, HTTP request rates, Solr query latencies, cache hit ratios |
| **Logs** | Loki | Structured application logs correlated with trace IDs |

Every MCP tool invocation creates a trace span: search, indexing (JSON, CSV, XML), collection operations (list, stats, health, create), and schema retrieval. All incoming HTTP requests and outgoing Solr calls are automatically traced.

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
| OTLP gRPC | localhost:4317 | Trace/metric/log ingestion (gRPC) |
| OTLP HTTP | localhost:4318 | Trace/metric/log ingestion (HTTP) |

**LGTM is never auto-started.** The `lgtm` service carries
`org.springframework.boot.ignore: "true"` in `compose.yaml`, which opts it out of Spring
Boot's Docker Compose lifecycle management. This is independent of transport mode: even
in HTTP mode, where `PROFILES=http ./gradlew bootRun` auto-starts (and stops) the `solr`
and `zoo` services, LGTM is untouched. Start it explicitly, either with the
`docker compose up -d` above (which starts everything) or, if `solr`/`zoo` are already
running via `bootRun`'s auto-start, on its own:

```bash
docker compose up -d lgtm
```

Because it's Boot-ignored, `bootRun` won't stop it either&mdash;bring it down by hand
when you're done:

```bash
docker compose stop lgtm
```

The `grafana/otel-lgtm` container stores everything in memory with no persistent volume,
so a restart discards all traces, metrics, and logs.

### Run the Server with Observability ###

```bash
PROFILES=http ./gradlew bootRun
```

The server auto-configures OTLP export when the LGTM stack is running. Default configuration:

```properties
management.tracing.sampling.probability=1.0     # 100% sampling (dev)
otel.exporter.otlp.endpoint=http://localhost:4317
otel.exporter.otlp.protocol=grpc
```

### Generate Some Activity ###

Grafana has nothing to show until at least one tool call runs. Any MCP client works
(the [Quick Start](tutorial.md) prompts are enough), or call the HTTP endpoint directly.
`HTTP_SECURITY_ENABLED=false` skips the OAuth2 setup for this local check&mdash;see
[Security](security/http.md) to keep it on:

```bash
HTTP_SECURITY_ENABLED=false PROFILES=http ./gradlew bootRun
```

```bash
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","method":"tools/call","id":1,"params":{"name":"list-collections","arguments":{}}}'
```

Run it a few times&mdash;each call is one trace, one set of log lines, and a handful of
metric samples.

***

## Grafana ##

Open [http://localhost:3000](http://localhost:3000) and click **Explore** in the left sidebar.

### View Traces (Tempo) ###

1. Select **Tempo** as the data source
2. Use TraceQL to search:

        {.service.name="solr-mcp"}

3. Click on a trace to see the span waterfall&mdash;each MCP tool invocation, Solr query, and HTTP request is a separate span

### View Logs (Loki) ###

1. Select **Loki** as the data source
2. Use LogQL to search:

        {service_name="solr-mcp"} |= "search"

3. Logs are automatically correlated with trace IDs&mdash;click a log line to jump to its trace

### View Metrics (Prometheus) ###

1. Select **Prometheus** as the data source
2. Example queries:

        # HTTP request rate
        rate(http_server_requests_seconds_count[5m])

        # JVM memory usage
        jvm_memory_used_bytes

        # Request latency (p99)
        histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m]))

### Pivoting Between the Three ###

The fastest way through all three signals for one request:

1. Find the trace in **Tempo** (TraceQL query above) and open it.
2. Each span carries a **Logs for this span** link&mdash;click it to jump straight to
   the matching lines in **Loki**, pre-filtered by trace ID. This works because the
   OTEL logback appender (`logback-spring.xml`) tags every log line with the active
   trace ID, the same one on the span you started from.
3. From a log line, the reverse link takes you back to its trace.
4. **Metrics** aren't per-request the same way&mdash;there's no single span/log &harr;
   metric-sample link&mdash;but the PromQL queries above will show the aggregate effect
   (e.g. a spike in `http_server_requests_seconds_count`) of whatever activity you just
   generated.

***

## Actuator Endpoints ##

The following health and metrics endpoints are exposed in HTTP mode:

```bash
curl http://localhost:8080/actuator/health       # Health check
curl http://localhost:8080/actuator/info          # Build info
curl http://localhost:8080/actuator/metrics       # Available metrics
curl http://localhost:8080/actuator/prometheus    # Prometheus scrape endpoint
curl http://localhost:8080/actuator/loggers       # Logger levels
```

***

## Troubleshooting ##

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| No traces/metrics/logs show up in Grafana at all | LGTM was never started&mdash;`bootRun` does not start it in either mode | `docker compose up -d lgtm` |
| Traces appear but stop after a restart | The `otel-lgtm` container has no persistent volume | Expected; re-run your workload after restarting `lgtm` |
| `otel.exporter.otlp.endpoint` connection refused | Running the server outside the `search` Docker network (e.g. inside its own container) while LGTM is on the host | Point `OTEL_TRACES_URL` at a reachable host, or join the same Docker network |
| Traces are sparse or missing under load | `management.tracing.sampling.probability` is below 1.0 | Raise it for the session you're debugging; keep it low in production |
| No data in **STDIO** mode | Tracing/metrics export is an HTTP-mode feature&mdash;STDIO has no servlet layer to instrument | Run `PROFILES=http ./gradlew bootRun` instead |

***

## Production Configuration ##

For production, reduce the sampling rate and configure the OTLP endpoint for your collector:

```bash
export OTEL_SAMPLING_PROBABILITY=0.1           # 10% sampling
export OTEL_TRACES_URL=https://otel-collector.example.com:4317
PROFILES=http java -jar build/libs/solr-mcp-1.0.0-SNAPSHOT.jar
```
