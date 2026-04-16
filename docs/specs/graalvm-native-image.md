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

Spring AI 1.1 ships most MCP-related hints. Expect to still need hints for:

- SolrJ: response beans, Jackson deserialization targets, concrete
  `SolrRequest` / `SolrResponse` subclasses actually used.
- Commons CSV: no known hints needed; verify.
- OpenTelemetry Spring Boot starter: verify it is native-friendly on its
  current version; if not, disable it in the native variant initially.
- MCP tool return types (records/POJOs returned from `@McpTool` methods):
  Spring AI's AOT processor should cover these; add manual
  `@RegisterReflectionForBinding` on any failing types.

Approach:
1. First pass: build and run `nativeTest`. Fix each reflection/resource
   failure by adding a targeted hint via `RuntimeHintsRegistrar` in a
   `@Configuration` class registered with `@ImportRuntimeHints`.
2. Only fall back to the agent (`-agentlib:native-image-agent`) if static
   analysis of the failures is too noisy. Agent output goes to
   `src/main/resources/META-INF/native-image/org.apache.solr/solr-mcp/`.

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

- **SolrJ native compatibility.** SolrJ historically relies on reflective
  codec discovery and XML/JavaBin deserialization. If hints become unwieldy,
  consider pinning the SolrJ response path to JSON only in the native build.
- **OpenTelemetry starter.** May require significant reachability metadata or
  version bump.
- **Spring Security + OAuth2 resource server.** Off by default in STDIO, but
  simply being on the classpath can drag AOT processing into code paths that
  fail. May need `@ConditionalOnProperty` gating or `@Profile("http")`.
- **Jib + distroless + glibc version.** Confirm the base image's glibc is
  compatible with the binary GraalVM produces on the CI image.
- **Build time & memory.** `nativeCompile` is RAM-hungry (commonly 4–8 GB).
  Ensure CI runners have headroom.

## 12. Out of Scope / Follow-ups

- Static linking with musl (`--static --libc=musl`) for a `scratch`-based
  image.
- HTTP profile native image (Actuator, Prometheus, OAuth2).
- Profile-Guided Optimization (PGO) builds.
- Publishing the native image from CI to GHCR / Docker Hub.
