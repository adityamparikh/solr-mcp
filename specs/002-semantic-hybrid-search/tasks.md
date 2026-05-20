# Tasks: Semantic & Hybrid Search

**Input**: Design documents from `/specs/002-semantic-hybrid-search/`
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/ ✅, quickstart.md ✅

**Tests**: Tests are REQUIRED by Constitution Principle III — unit tests (`*Test.java`) with mocked Solr, integration tests (`*IntegrationTest.java`) with real Solr via Testcontainers. Write tests first (TDD).

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- Constitution Principle III requires tests to be written and failing BEFORE implementation

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Add new dependency and create shared infrastructure classes that all user stories depend on.

- [ ] T001 Add `spring-ai-vector-store` library entry to `gradle/libs.versions.toml` and `implementation(libs.spring.ai.vector.store)` to `build.gradle.kts`
- [ ] T002 Create `SearchMode` enum (`KEYWORD`, `SEMANTIC`, `HYBRID`) in `src/main/java/org/apache/solr/mcp/server/search/SearchMode.java`
- [ ] T003 Create `EmbeddingService` spring `@Service` in `src/main/java/org/apache/solr/mcp/server/search/EmbeddingService.java` — wraps `ObjectProvider<EmbeddingModel>`, exposes `isConfigured()`, `embed(String)`, `embedForIndexing(List<String>)`; throws `McpException` with human-readable message when model absent and `embed` is called explicitly
- [ ] T004 Run `./gradlew spotlessApply && ./gradlew build` to verify setup compiles with no failures

**Checkpoint**: New dependency available; `SearchMode` and `EmbeddingService` compile and pass build.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Write unit tests for `EmbeddingService` (the shared foundation for all three user stories) and verify they fail before any implementation. Then implement and verify green.

**⚠️ CRITICAL**: This phase must be complete before user story implementation can begin.

- [ ] T005 Write `EmbeddingServiceTest` in `src/test/java/org/apache/solr/mcp/server/search/EmbeddingServiceTest.java` — unit tests covering: `isConfigured()` returns false when no `EmbeddingModel` bean present; `isConfigured()` returns true when bean present; `embed(String)` throws descriptive `McpException` when not configured; `embed(String)` delegates to model when configured; `embedForIndexing(List<String>)` concatenates values and embeds; `embedForIndexing` with empty list returns null without calling model
- [ ] T006 Run `./gradlew test --tests "EmbeddingServiceTest"` and confirm all tests FAIL (implementation not yet complete)
- [ ] T007 Implement `EmbeddingService` (stubbed in T003) to pass all `EmbeddingServiceTest` tests
- [ ] T008 Run `./gradlew test --tests "EmbeddingServiceTest"` and confirm all tests PASS
- [ ] T009 Run `./gradlew build` — full build must be green before proceeding

**Checkpoint**: `EmbeddingService` is fully tested and green; user story implementation can begin.

---

## Phase 3: User Story 1 — Zero-Configuration Best-Quality Search (Priority: P1) 🎯 MVP

**Goal**: Extend the `search` tool with `mode`, `topK`, and `vectorField` parameters. Implement smart server default (hybrid when embedding configured, keyword when not). Implement semantic and hybrid search logic including RRF merge.

**Independent Test**: Call `search` with no `mode` parameter against a Solr 9+ collection with vector embeddings and an embedding model configured; verify semantically similar documents appear in results (e.g., "affordable accommodation" returns "budget hotel" documents).

### Tests for User Story 1 (write FIRST, run to confirm they FAIL)

- [ ] T010 [P] [US1] Write `SearchServiceTest` additions in `src/test/java/org/apache/solr/mcp/server/search/SearchServiceTest.java` — unit tests covering: smart default resolves to `HYBRID` when `EmbeddingService.isConfigured()` true; smart default resolves to `KEYWORD` when `EmbeddingService.isConfigured()` false; `mode="semantic"` errors with clear message when not configured; `mode="hybrid"` errors with clear message when not configured; `mode="keyword"` always proceeds regardless of embedding config; invalid `mode` value returns validation error listing accepted values; `numFound` for semantic/hybrid equals `results.size()` not `getNumFound()`; `vectorField` defaults to `"vector"`; `topK` defaults to 10; RRF merge ranks documents present in both lists ahead of those in only one list (use known-rank test data)
- [ ] T011 [P] [US1] Write `SearchServiceSemanticIntegrationTest` in `src/test/java/org/apache/solr/mcp/server/search/SearchServiceSemanticIntegrationTest.java` — integration tests using Testcontainers with `solr:9.9-slim` image: index two documents with distinct topics and pre-computed embeddings, call `search` with `mode=semantic`, verify both retrieve correctly ranked; call `search` with `mode=hybrid`, verify keyword-only and semantic-only documents both appear; call `search` with no `mode` and embedding model configured, verify hybrid results returned; verify `numFound` ≤ `topK` for all vector modes
- [ ] T012 [US1] Run `./gradlew test --tests "SearchServiceTest"` (US1 additions) and `./gradlew test --tests "SearchServiceSemanticIntegrationTest"` — confirm all new tests FAIL

### Implementation for User Story 1

- [ ] T013 [US1] Update `SearchService.search(...)` method signature in `src/main/java/org/apache/solr/mcp/server/search/SearchService.java` — add three nullable parameters: `String mode`, `Integer topK`, `String vectorField`; inject `EmbeddingService` via constructor; wire smart default logic using `SearchMode` enum and `EmbeddingService.isConfigured()`; add `@McpTool` description update documenting `numFound` behavioral difference for semantic/hybrid modes
- [ ] T014 [US1] Implement semantic search in `SearchService` — embed query via `EmbeddingService.embed(query)`, build `{!knn f=<vectorField> topK=<topK>}<embedding>` query, issue via `SolrRequest.METHOD.POST`, apply `filterQueries` as `fq`, set `numFound = response.getResults().size()`, catch `RemoteSolrException` and return clear error if Solr version does not support KNN
- [ ] T015 [US1] Implement hybrid search in `SearchService` — issue keyword search (existing logic, `rows=topK`) and semantic search (T014) independently; implement RRF merge `score = 1/(60+keywordRank) + 1/(60+semanticRank)` in application code; return top `topK` documents by RRF score; include facets from keyword leg; set `numFound = merged result count`
- [ ] T016 [US1] Add input validation in `SearchService` — validate `mode` is one of `keyword`/`semantic`/`hybrid` when supplied; return `McpException` with message `"Invalid mode '<value>'. Accepted values: keyword, semantic, hybrid."` for unknown values
- [ ] T017 [US1] Update `McpToolRegistrationTest` in `src/test/java/org/apache/solr/mcp/server/McpToolRegistrationTest.java` — update `testSearchServiceHasToolAnnotation` to use new 10-parameter method signature `(String, String, List, List, List, Integer, Integer, String, Integer, String)`
- [ ] T018 [US1] Run `./gradlew test --tests "SearchServiceTest"` and `./gradlew test --tests "SearchServiceSemanticIntegrationTest"` — all tests must PASS
- [ ] T019 [US1] Run `./gradlew test --tests "McpToolRegistrationTest"` — must PASS
- [ ] T020 [US1] Run `./gradlew build` — full build green

**Checkpoint**: Smart default, semantic, and hybrid search fully functional and tested. User Story 1 independently verifiable via `SearchServiceSemanticIntegrationTest`.

---

## Phase 4: User Story 2 — Unchanged Behavior Without Embedding Model (Priority: P1)

**Goal**: Verify that when no embedding model is configured, all existing `search` and `index-json-documents` calls produce behavior identical to pre-2.0.0. Smart default silently falls back to keyword; explicit semantic/hybrid returns clear error.

**Independent Test**: Start server with no embedding configuration; call `search` with no `mode` parameter; verify response is identical to keyword-mode response. Call `search` with `mode=semantic` and verify a clear error (not a stack trace) is returned.

**Note**: Most behavioral guarantees for this story are already covered by unit tests in T010 (smart default resolves to `KEYWORD` when not configured; explicit semantic/hybrid returns error). This phase adds integration-level regression tests and verifies the server starts cleanly.

### Tests for User Story 2 (write FIRST, run to confirm they FAIL)

- [ ] T021 [US2] Write `SearchServiceBackwardCompatIntegrationTest` in `src/test/java/org/apache/solr/mcp/server/search/SearchServiceBackwardCompatIntegrationTest.java` — integration tests using Testcontainers with no `EmbeddingModel` bean in context: `search` with no `mode` returns same response as explicit `mode=keyword` for identical query; explicit `mode=semantic` returns human-readable error string (no stack trace); explicit `mode=hybrid` returns human-readable error string; all pre-2.0.0 parameters (`filterQueries`, `facetFields`, `sortClauses`, `start`, `rows`) behave identically to pre-2.0.0
- [ ] T022 [US2] Run `./gradlew test --tests "SearchServiceBackwardCompatIntegrationTest"` — confirm new tests FAIL (implementation from US1 may already make some pass; focus on error-message and response-equality assertions)

### Implementation for User Story 2

- [ ] T023 [US2] Verify `SearchService.search(...)` already satisfies backward-compat contract from T013–T016 — review error message text against spec SC-005 requirement ("clear, human-readable error message, not a stack trace"); adjust error message wording if needed in `src/main/java/org/apache/solr/mcp/server/search/SearchService.java`
- [ ] T024 [US2] Run `./gradlew test --tests "SearchServiceBackwardCompatIntegrationTest"` — all tests must PASS
- [ ] T025 [US2] Run `./gradlew test -Dsolr.test.image=solr:8.11-slim --tests "*IntegrationTest"` — verify keyword search works on 8.11 and semantic/hybrid returns the KNN-unsupported error (not a crash)
- [ ] T026 [US2] Run `./gradlew build` — full build green

**Checkpoint**: Zero behavioral regression confirmed for operators who do not configure an embedding model. Backward compatibility verified across Solr versions.

---

## Phase 5: User Story 3 — Index Documents with Auto-Generated Embeddings (Priority: P2)

**Goal**: Extend all three indexing tools (`index-json-documents`, `index-csv-documents`, `index-xml-documents`) with optional `generateEmbeddings` and `textFields` parameters. Generate and store vector embeddings at index time so documents are immediately searchable via smart hybrid default. All three format parsers preserve field names in the resulting `SolrInputDocument`, making the approach format-agnostic.

**Independent Test**: Call any of the three index tools with `generateEmbeddings=true` and `textFields=["title","body"]`; then call `search` with no `mode` (triggering hybrid default); verify indexed documents appear in results, including semantic matches.

### Tests for User Story 3 (write FIRST, run to confirm they FAIL)

- [ ] T027 [P] [US3] Write `IndexingServiceEmbeddingTest` in `src/test/java/org/apache/solr/mcp/server/indexing/IndexingServiceEmbeddingTest.java` — unit tests with mocked `EmbeddingService` covering all three format methods: `generateEmbeddings=false` (or absent) indexes documents without calling `EmbeddingService`; `generateEmbeddings=true` calls `EmbeddingService.embedForIndexing` per document for JSON, CSV, and XML inputs; document missing all `textFields` is indexed without vector field and counted in `skipped`; document missing some (not all) `textFields` generates embedding from present fields; `generateEmbeddings=true` with no embedding model configured returns error immediately without indexing any document; response reports correct `indexed`, `skipped` counts for each format
- [ ] T028 [P] [US3] Write `IndexingServiceEmbeddingIntegrationTest` in `src/test/java/org/apache/solr/mcp/server/indexing/IndexingServiceEmbeddingIntegrationTest.java` — integration test using Testcontainers with Solr 9+: index JSON, CSV, and XML documents each with `generateEmbeddings=true`; subsequently call `search` with no `mode`; verify all indexed documents appear in hybrid results; verify `skipped` count correct when text fields absent
- [ ] T029 [US3] Run `./gradlew test --tests "IndexingServiceEmbeddingTest"` and `./gradlew test --tests "IndexingServiceEmbeddingIntegrationTest"` — confirm all new tests FAIL

### Implementation for User Story 3

- [ ] T030 [US3] Extract shared embedding generation helper method in `src/main/java/org/apache/solr/mcp/server/indexing/IndexingService.java` — private method `applyEmbeddings(List<SolrInputDocument> docs, List<String> textFields)` that iterates documents, calls `EmbeddingService.embedForIndexing`, sets `"vector"` field on each `SolrInputDocument`, tracks `skipped` count; used by all three format methods
- [ ] T031 [US3] Update all three method signatures in `IndexingService` — add two nullable parameters (`Boolean generateEmbeddings`, `List<String> textFields`) to `indexJsonDocuments`, `indexCsvDocuments`, and `indexXmlDocuments`; inject `EmbeddingService` via constructor; each method calls `applyEmbeddings` when `generateEmbeddings=true` before calling `indexDocuments`; validate embedding model configured and return error immediately if not
- [ ] T032 [US3] Return structured response from all three indexing methods reporting `indexed`, `skipped`, and `errors` counts — update return type to satisfy FR-006 and SC-004; update `@McpTool` descriptions accordingly in `src/main/java/org/apache/solr/mcp/server/indexing/IndexingService.java`
- [ ] T033 [US3] Update `McpToolRegistrationTest` in `src/test/java/org/apache/solr/mcp/server/McpToolRegistrationTest.java` — update method signature assertions for all three indexing tools to include new parameters
- [ ] T034 [US3] Run `./gradlew test --tests "IndexingServiceEmbeddingTest"` and `./gradlew test --tests "IndexingServiceEmbeddingIntegrationTest"` — all tests must PASS
- [ ] T035 [US3] Run `./gradlew build` — full build green

**Checkpoint**: End-to-end embedding generation and hybrid retrieval working for JSON, CSV, and XML inputs. Documents indexed with `generateEmbeddings=true` via any format appear in smart-default hybrid search results.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Solr version matrix testing, observability, and documentation.

- [ ] T036 [P] Run Solr version compatibility matrix for all integration tests — `./gradlew test -Dsolr.test.image=solr:9.4-slim --tests "*IntegrationTest"`, `./gradlew test -Dsolr.test.image=solr:9.10-slim --tests "*IntegrationTest"`, `./gradlew test -Dsolr.test.image=solr:10-slim --tests "*IntegrationTest"` — all must pass or produce expected error messages on unsupported operations
- [ ] T037 [P] Run Solr 8.11 compatibility check — `./gradlew test -Dsolr.test.image=solr:8.11-slim --tests "*IntegrationTest"` — keyword tests must pass; semantic/hybrid tests must return clear error, not crash
- [ ] T038 [P] Verify `@Observed` metrics are still emitted correctly on `SearchService.search` and `IndexingService.indexJsonDocuments` after method signature changes — confirm no Micrometer observation breaks
- [ ] T039 Run `./gradlew spotlessApply` — apply code formatting to all modified/created files
- [ ] T040 Run `./gradlew build` — final full build with all tests green across default Solr version
- [ ] T041 [P] Validate quickstart.md against implementation — follow steps in `specs/002-semantic-hybrid-search/quickstart.md` manually or via scripted test; confirm all commands and error messages match actual behavior

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 completion — blocks all user stories
- **US1 (Phase 3)**: Depends on Phase 2 completion
- **US2 (Phase 4)**: Depends on Phase 3 completion (uses US1 `SearchService` implementation)
- **US3 (Phase 5)**: Depends on Phase 2 completion — can run in parallel with US2 after Phase 2
- **Polish (Phase 6)**: Depends on Phases 3, 4, 5 all complete

### User Story Dependencies

- **US1 (P1)**: Can start after Foundational — no dependencies on US2/US3
- **US2 (P1)**: Depends on US1 `SearchService` implementation (shares same class)
- **US3 (P2)**: Can start after Foundational independently of US1/US2 — different class (`IndexingService`)

### Within Each User Story

1. Write tests → run to confirm FAIL
2. Implement
3. Run tests → confirm PASS
4. Run `./gradlew build`
5. Checkpoint

### Parallel Opportunities

- **Phase 1**: T002 and T003 can run in parallel (different files)
- **Phase 3**: T010 (unit tests) and T011 (integration tests) can be written in parallel
- **Phase 5**: T027 (unit tests) and T028 (integration tests) can be written in parallel
- **After Phase 2**: US3 (IndexingService) can be worked on in parallel with US1+US2 (SearchService) by a second developer
- **Phase 6**: T036, T037, T038, T041 can all run in parallel

---

## Parallel Example: User Story 1

```bash
# Write both test classes simultaneously (different files):
# Developer A writes SearchServiceTest additions (T010)
# Developer B writes SearchServiceSemanticIntegrationTest (T011)

# Run failing tests in parallel:
./gradlew test --tests "SearchServiceTest" &
./gradlew test --tests "SearchServiceSemanticIntegrationTest" &
wait

# Implement (sequential, same file SearchService.java): T013 → T014 → T015 → T016
# Then run all tests green: T018, T019, T020
```

---

## Implementation Strategy

### MVP First (User Stories 1 + 2 only)

1. Complete Phase 1: Setup (T001–T004)
2. Complete Phase 2: Foundational (T005–T009)
3. Complete Phase 3: User Story 1 (T010–T020) — smart default + semantic + hybrid search
4. Complete Phase 4: User Story 2 (T021–T026) — backward compat verification
5. **STOP and VALIDATE**: Smart default, semantic, hybrid, and keyword-fallback all working
6. Demo or release: operators without embedding model have zero regression; operators with embedding model get automatic hybrid search

### Full Delivery (add User Story 3)

7. Complete Phase 5: User Story 3 (T027–T035) — embedding generation at index time
8. Complete Phase 6: Polish (T036–T041)
9. **VALIDATE**: End-to-end: index with embeddings → smart default hybrid search returns results

### Parallel Team Strategy

With two developers after Phase 2 completes:

- **Developer A**: Phase 3 (US1, SearchService) → Phase 4 (US2, backward compat)
- **Developer B**: Phase 5 (US3, IndexingService embedding generation)
- Both merge → Phase 6 (Polish) together

---

## Notes

- [P] tasks = different files, no dependencies between them
- All `*IntegrationTest.java` tests require Docker daemon running (Testcontainers)
- Solr version matrix tests (T036, T037) must be run manually or in CI — not part of default `./gradlew build`
- `McpToolRegistrationTest` uses reflection on method signatures — must be updated (T017, T033) before adding new parameters or build will break
- Never skip `./gradlew spotlessApply` before commit (T039)
- Use `git commit -s` for all commits (Signed-off-by required per CLAUDE.md)
- `numFound` for KNN: use `response.getResults().size()` not `response.getResults().getNumFound()`
