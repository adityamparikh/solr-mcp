# Spec: GraalVM Native Image Support (Opt-In, Jib, STDIO)

Status: Draft
Owner: TBD
Target branch: `claude/graalvm-native-image-support-u1RqL`
Related: [Spring AI 1.1 blog post](https://spring.io/blog/2025/05/20/your-first-spring-ai-1)

## 1. Motivation

The Docker image produced by Jib currently ships the app on `eclipse-temurin:25-jre`.
For the **local STDIO use case** (Claude Desktop launching the container on demand
per session), JVM cold start and memory overhead are the main pain points:

- Each new session pays the JVM warm-up cost.
- Idle memory of a Spring Boot + Spring AI + SolrJ process is substantial even before
  it does any work.
- The image is hundreds of MB.

A GraalVM native image trades build-time complexity for:

- Sub-second startup.
- Significantly lower RSS.
- A smaller, self-contained image (no JRE layer).

Spring AI 1.1 added first-class AOT/native support, which makes this tractable
for this project.

## 2. Goals

1. Add an **opt-in** native image build path, triggered by a Gradle property
   (`-Pnative`), that produces a Docker image via Jib.
2. Keep the default build (JVM mode) unchanged.
3. Prove correctness: the existing test suite passes under `nativeTest`.
4. Prove the win: a reproducible benchmark script measures startup time,
   resident memory, and image size for JVM vs native, and the results are
   recorded in this spec.
5. Target transport: **STDIO profile only** for the initial cut. HTTP mode is
   out of scope for v1 but not precluded.

## 3. Non-Goals

- Replacing the JVM image. Both flavors ship.
- Cross-compiling native binaries from macOS to Linux — native build happens
  in a Linux context (CI or a Linux dev box / container).
- Native image support for the HTTP profile (OAuth2, actuator, Prometheus
  registry). These often need extra reachability metadata; deferred to a
  follow-up.
- JMH-style throughput benchmarks. Startup / RSS / disk only.

## 4. High-Level Approach

1. Add the **GraalVM Native Build Tools** plugin
   (`org.graalvm.buildtools.native`) alongside the existing Spring Boot plugin.
   Spring Boot's AOT tasks (`processAot`, `processTestAot`) are picked up
   automatically.
2. Gate native-specific configuration behind a Gradle property:
   ```bash
   ./gradlew jibDockerBuild -Pnative
   ```
   When `-Pnative` is present:
   - `nativeCompile` runs first and produces a statically-ish linked ELF binary.
   - Jib is reconfigured to ship that binary on a minimal base image instead of
     the JRE + classpath layout.
3. Switch Jib's base image for native to a small Linux image with glibc
   (e.g. `gcr.io/distroless/base-debian12` or `cgr.dev/chainguard/glibc-dynamic`).
   Do **not** use `scratch` — GraalVM native images still need glibc and a few
   shared libs unless `--static --libc=musl` is used (follow-up).
4. Entry point becomes the native binary, not `java -jar`. `jvmFlags` and
   `mainClass` on the Jib container block are dropped for the native variant.
5. Native tests (`nativeTest`) run in a separate CI job, not as part of
   `./gradlew build`, because they are slow (image compile per run).

### 4.1 Why Jib (not bootBuildImage)

`bootBuildImage` (buildpacks) logs to stdout during startup in the resulting
container, which corrupts MCP's STDIO framing — this is already documented in
CLAUDE.md as the reason Jib is used for the JVM image. The same constraint
applies to the native image.

Jib does not "build native" on its own; we use it to **package an
already-compiled native binary**. The native compile is done by
`org.graalvm.buildtools.native` (`nativeCompile` task); Jib's job is to put
the binary plus any required resources onto a base image.

## 5. Gradle Changes

### 5.1 Plugin

`build.gradle.kts` plugins block:
```kotlin
alias(libs.plugins.graalvm.native)       // new, version-catalogued
```

`gradle/libs.versions.toml`:
```toml
[versions]
graalvm-native = "0.10.6"   # latest at time of writing; verify

[plugins]
graalvm-native = { id = "org.graalvm.buildtools.native", version.ref = "graalvm-native" }
```

### 5.2 Toolchain

`nativeCompile` requires a GraalVM toolchain. Pin it explicitly so the native
build uses GraalVM JDK 25, while the regular `javaCompile` continues to use
the existing toolchain:

```kotlin
graalvmNative {
    binaries {
        named("main") {
            javaLauncher = javaToolchains.launcherFor {
                languageVersion = JavaLanguageVersion.of(25)
                vendor = JvmVendorSpec.GRAAL_VM
            }
            buildArgs.addAll(
                "--no-fallback",
                "-H:+ReportExceptionStackTraces"
            )
        }
        named("test") {
            javaLauncher = javaToolchains.launcherFor {
                languageVersion = JavaLanguageVersion.of(25)
                vendor = JvmVendorSpec.GRAAL_VM
            }
        }
    }
}
```

The Gradle toolchain auto-provisioning config (or a local Foojay resolver)
must be able to download GraalVM CE 25; document this in README.

### 5.3 Opt-in flag

```kotlin
val nativeBuild = project.hasProperty("native")
```

When `nativeBuild` is true:
- Declare `tasks.named("jib")` and `tasks.named("jibDockerBuild")` to
  `dependsOn(tasks.named("nativeCompile"))`.
- Replace Jib's container config with a native-specific block
  (see section 5.4). Otherwise the existing JVM config is used unchanged.

### 5.4 Jib config for native

```kotlin
if (nativeBuild) {
    jib {
        from { image = "gcr.io/distroless/base-debian12" }
        container {
            mainClass = null          // not used for native
            jvmFlags = emptyList()
            entrypoint = listOf("/app/solr-mcp")
            environment = mapOf("SPRING_DOCKER_COMPOSE_ENABLED" to "false")
        }
        extraDirectories {
            paths {
                path {
                    setFrom(layout.buildDirectory.dir("native/nativeCompile"))
                    into = "/app"
                    includes.set(listOf("solr-mcp"))
                }
            }
        }
        // Image tag distinguishes native from JVM
        to { image = "solr-mcp:$version-native"; tags = setOf("latest-native") }
    }
}
```

### 5.5 Native tests

No extra config needed beyond the plugin — `./gradlew nativeTest` is provided
by `org.graalvm.buildtools.native`. We do **not** wire it into `./gradlew build`.
It is invoked explicitly in a dedicated CI job.

Docker integration tests (`@Tag("docker-integration")`) should gain a
native counterpart that builds the `-native` image and re-runs the STDIO
integration scenario against it. This is the end-to-end proof.

## 6. Reflection / Resource Hints

### 6.1 SolrJ (JSON wire format only)

The client is constructed in
`src/main/java/org/apache/solr/mcp/server/config/SolrConfig.java:174` as:

```java
new HttpJdkSolrClient.Builder(url)
    .withResponseParser(jsonResponseParser)   // JSON, not JavaBin
    .build();
```

This means the **JavaBin codec path is not taken**, which is the SolrJ
surface most frequently cited as native-hostile (heavy reflective
dispatch over `NamedList`, `EnumFieldValue`, `Date`, `SolrDocument`,
etc.). The XML response parser is similarly out of scope.

Concrete SolrJ surface the native image must cover:

| Area | Native risk | Notes |
| --- | --- | --- |
| `HttpJdkSolrClient` | Low | Uses the JDK `HttpClient`; no Netty/HttpComponents reflection. |
| `JsonResponseParser` | Low | Delegates to Jackson, which Spring Boot AOT already instruments. |
| `QueryResponse` / `UpdateResponse` fields returned to callers | Low-medium | Concrete classes used are narrow; add `@RegisterReflectionForBinding` only if `nativeTest` flags them. |
| `NamedList<Object>` | Low | Present in the mbeans admin path used by `CollectionService.getCacheMetrics()` / `getHandlerMetrics()`. Those methods already catch `RuntimeException` and return `null` on Solr 10, so reflection failures there are non-fatal. |
| `ServiceLoader` contributions (codec, stream factories) | Medium | Need reachability metadata or explicit resource hints for `META-INF/services/*`. Solve via `HintsRegistrar` if discovered failures appear. |
| `solr-solrj` 9.x native metadata | Not published | Upstream does not ship `reachability-metadata.json`. We will generate hints locally. |

Mitigation order:
1. Run `nativeTest` against the JSON-only path; collect failures.
2. Add targeted `RuntimeHintsRegistrar` entries alongside `SolrConfig`
   (e.g. `SolrReflectionHints.java`).
3. Only if failure volume is large, run once with
   `-agentlib:native-image-agent=config-output-dir=...` over the real test
   suite and commit the generated config under
   `src/main/resources/META-INF/native-image/org.apache.solr/solr-mcp/`.

### 6.2 OpenTelemetry / Micrometer tracing

Good news: the **OpenTelemetry Spring Boot Starter explicitly supports
native image** (it is recommended *over* the Java agent for native
deployments). See OpenTelemetry's Spring starter docs and the
`opentelemetry-java-examples/spring-native` sample.

Caveats that matter for this project:

- Version drift: `build.gradle.kts:105` pins
  `opentelemetry-instrumentation-bom:2.11.0` but
  `gradle/libs.versions.toml:24` declares `2.26.1`. Only the BOM
  coordinate actually drives resolution, so **2.11.0 is in effect**.
  Recommend bumping to the catalog version (and reconciling the two
  sources of truth) as part of this work, because reachability metadata
  has been actively improved in the 2.20+ line.
- OTLP/gRPC exporter: the endpoint is configured in
  `application-http.properties:23` (`otel.exporter.otlp.endpoint=...`,
  `otel.exporter.otlp.protocol=grpc`) and is **not referenced by
  `application-stdio.properties`**. gRPC exporters historically need the
  most native hints; by targeting STDIO we sidestep this — but the
  gRPC-related classes are still on the classpath.
- `io.micrometer:micrometer-tracing-bridge-otel` — bridges Micrometer
  Observation to OTel. Native-compatible in current versions (Boot 3.4+);
  `@Observed` annotations (`IndexingService.java`, `SearchService.java`,
  `SchemaService.java`, `CollectionService.java`) rely on Spring AOP,
  which AOT supports via proxy hints emitted by Spring Boot.
- `io.micrometer:micrometer-registry-prometheus` — only meaningful under
  HTTP profile (it's scraped via `/actuator/prometheus`). Bean is created
  unconditionally but harmless in STDIO. Native-compatible.

Action for v1 (STDIO only): do nothing proactive; let `nativeTest` tell
us. If reachability complaints surface from the OTLP exporter path,
either (a) bump to OTel BOM 2.26+, or (b) put the OTLP exporter
configuration behind `@Profile("http")` so those classes are never
wired under STDIO (they are already only configured via
`application-http.properties`).

### 6.3 Security / OAuth2 on classpath under STDIO

Current state in the repo (already good):

- `src/main/resources/application-stdio.properties:7-9` already excludes
  `SecurityAutoConfiguration` and
  `ManagementWebSecurityAutoConfiguration` via
  `spring.autoconfigure.exclude`.
- `HttpSecurityConfiguration.java:34` is annotated `@Profile("http")`.
- `MethodSecurityConfiguration.java:32` is `@Profile("http")`.
- No `@Import` or static references pull these classes into the STDIO
  bean graph.

Remaining concern: Spring Boot AOT runs with a **specific active
profile**. If AOT runs without `spring.profiles.active=stdio`, the AOT
processor may still try to process the security autoconfiguration,
potentially emit hints for it, and/or fail on missing OAuth2 config at
processing time.

Mitigation:

1. **Pin the AOT profile.** In the Gradle `processAot` / `processTestAot`
   task configuration (only when `-Pnative`), set:
   ```kotlin
   tasks.named<JavaExec>("processAot") {
       args("--spring.profiles.active=stdio")
   }
   tasks.named<JavaExec>("processTestAot") {
       args("--spring.profiles.active=stdio")
   }
   ```
   This ensures `application-stdio.properties` (with its
   `spring.autoconfigure.exclude`) is active during AOT, so security
   autoconfig is excluded before hint generation runs.
2. **Make exclusions compile-time too, as a belt-and-suspenders step.**
   Add `@SpringBootApplication(exclude = {...})` to `Main` for the two
   security autoconfig classes. The property-based exclusion already in
   `application-stdio.properties` is runtime; the annotation-based
   exclusion is seen by AOT unconditionally.
3. **Leave the jars on the classpath.** Removing `spring-boot-starter-
   security` and the OAuth2 resource server starter would break the HTTP
   profile and the JVM Docker image. Native image's dead-code elimination
   will drop unused classes; what matters is that AOT does not *try* to
   wire them.
4. If residual reachability failures show up from the MCP security
   bridge (`mcp-server-security`), confirm it does not have eager
   `@Configuration` classes that load outside `@Profile("http")`. If it
   does, wrap our usage with `@ConditionalOnProperty`.

### 6.4 Hints workflow

1. First pass: build and run `nativeTest` with `-Pnative`. Fix each
   reflection/resource failure by adding a targeted hint via a
   `RuntimeHintsRegistrar` in a `@Configuration` class registered with
   `@ImportRuntimeHints`. Preferred over annotation-scattering because
   the rules are centralized and reviewable.
2. Only fall back to the agent (`-agentlib:native-image-agent`) if
   static analysis of the failures is too noisy. Agent output goes to
   `src/main/resources/META-INF/native-image/org.apache.solr/solr-mcp/`
   and is committed.

## 7. Profile / Application Config

- Native v1 targets the STDIO profile. The native image's default profile
  must be `stdio`. Either bake it into the Jib entrypoint
  (`entrypoint = listOf("/app/solr-mcp", "--spring.profiles.active=stdio")`)
  or rely on `SPRING_PROFILES_ACTIVE=stdio` env var set in the Jib
  `environment` map.
- Actuator, Prometheus registry, Security starters: keep on the classpath but
  verify they do not fail AOT processing in STDIO mode. If any fails, gate it
  behind `@Profile("http")` so it is not loaded under STDIO.

## 8. Benchmark Plan

### 8.1 Script

`scripts/benchmark-native.sh` (new). Requirements:
- Runs on Linux (CI or Linux dev box).
- Builds both images:
  - `./gradlew jibDockerBuild` → `solr-mcp:<v>` (JVM)
  - `./gradlew jibDockerBuild -Pnative` → `solr-mcp:<v>-native`
- For each image, measures:
  - **Image size on disk** via `docker image inspect <img> --format '{{.Size}}'`.
  - **Startup time**: time from `docker run` until the container prints its
    MCP "server ready" signal on stdout (STDIO mode). If no such signal
    exists, add one in `Main` behind a `solr.mcp.startup.log=true` flag that
    is enabled for benchmarks only.
  - **Memory after startup**: after server-ready, sample
    `docker stats --no-stream --format '{{.MemUsage}}'` N times over 5 seconds,
    record the minimum (steady-state idle RSS).
  - **Memory after one search**: drive one MCP `search` call via the existing
    client harness, then resample.
- Each measurement is the median of 5 runs.
- Output a markdown table to stdout and write `docs/specs/benchmark-results.md`.

### 8.2 Results table (to be filled in after implementation)

| Metric                          | JVM (`:<v>`) | Native (`:<v>-native`) | Delta |
|---------------------------------|--------------|------------------------|-------|
| Image size (MB)                 | TBD          | TBD                    | TBD   |
| Cold start (ms)                 | TBD          | TBD                    | TBD   |
| Idle RSS after start (MB)       | TBD          | TBD                    | TBD   |
| RSS after first search (MB)     | TBD          | TBD                    | TBD   |
| `nativeTest` wall-clock         | n/a          | TBD                    | n/a   |

### 8.3 Acceptance thresholds

The native image is considered a win for the STDIO use case if **all** hold:
- Startup ≤ 25% of JVM startup.
- Idle RSS ≤ 50% of JVM idle RSS.
- Image size ≤ 60% of JVM image size.

If any threshold fails, capture the numbers anyway and keep the native
path as opt-in behind the same flag; document the gap.

## 9. CI

Add a separate GitHub Actions workflow (or job in the existing one)
`native.yml`:
- Triggers: `workflow_dispatch` and on PRs touching this spec, the native
  config, or `gradle/libs.versions.toml`.
- Steps:
  1. Set up GraalVM JDK 25 (via `graalvm/setup-graalvm@v1`).
  2. `./gradlew nativeTest`
  3. `./gradlew jibDockerBuild -Pnative`
  4. `./gradlew dockerIntegrationTest -Pnative` (native-mode variant of the
     STDIO integration test).
  5. `scripts/benchmark-native.sh` — upload results table as a job artifact.

The default PR build (`./gradlew build`) remains JVM-only and fast.

## 10. Rollout

1. Land Gradle plumbing behind `-Pnative` with no native-specific hints.
2. Iterate on hints until `nativeTest` is green.
3. Add `dockerIntegrationTest -Pnative` path and the STDIO integration test
   variant.
4. Land `scripts/benchmark-native.sh` and fill in section 8.2.
5. Update root README with a "Native image (experimental)" section pointing
   at this spec, plus the one-liner build command.
6. Tag the native image as `:latest-native` so users can opt in on pull:
   `docker pull solr-mcp:latest-native`.

## 11. Risks & Open Questions

- **SolrJ native compatibility.** *Downgraded from medium to low-medium.*
  The project already uses `JsonResponseParser` on `HttpJdkSolrClient`,
  avoiding the JavaBin/XML reflective surface. Residual risk is
  `ServiceLoader` metadata and a narrow set of response bean fields;
  handle via a local `RuntimeHintsRegistrar`. See §6.1.
- **OpenTelemetry starter.** *Downgraded from medium to low for STDIO.*
  The starter is officially native-supported. Action items: bump the
  OTel instrumentation BOM (currently 2.11.0 in `build.gradle.kts`,
  catalog says 2.26.1 — inconsistent), and note that the OTLP/gRPC
  exporter is only wired in the HTTP profile, so the STDIO native image
  does not exercise its reflection surface. See §6.2.
- **Spring Security + OAuth2 resource server.** *Downgraded.* Already
  excluded via `spring.autoconfigure.exclude` in
  `application-stdio.properties` and all security config classes carry
  `@Profile("http")`. The new risk is AOT-time: must pin
  `spring.profiles.active=stdio` on `processAot`/`processTestAot` so the
  exclusions are applied before hint generation, and mirror the
  exclusions at annotation level on `Main` for defense in depth. See §6.3.
- **AOT profile correctness.** New risk surfaced during investigation:
  AOT runs once at build time with one profile active. Building the
  native image with the wrong (or no) profile means the wrong bean
  graph is captured. Non-trivial: STDIO and HTTP diverge heavily. V1
  commits to STDIO only; HTTP native is an explicit follow-up.
- **Jib + distroless + glibc version.** Confirm the base image's glibc is
  compatible with the binary GraalVM produces on the CI image.
- **Build time & memory.** `nativeCompile` is RAM-hungry (commonly 4–8 GB).
  Ensure CI runners have headroom.
- **`mcp-server-security` library.** Small, non-Spring-official. Verify
  it has no eager `@Configuration` outside `@Profile("http")` that would
  force its classes into the STDIO AOT graph.

## 12. Out of Scope / Follow-ups

- Static linking with musl (`--static --libc=musl`) for a `scratch`-based
  image.
- HTTP profile native image (Actuator, Prometheus, OAuth2).
- Profile-Guided Optimization (PGO) builds.
- Publishing the native image from CI to GHCR / Docker Hub.
