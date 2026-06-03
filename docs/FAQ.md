# Frequently Asked Questions

## LLMs already know how to talk to Solr. Could this MCP server be replaced by a Solr "skill" for agents?

This is the right question to ask in 2026, and it deserves a careful answer
because it goes to the *raison d'être* of the project. The short version,
borrowing the framing the industry has converged on: **Skills and MCP are not
competing standards — they're complementary layers of the agentic stack.** A
skill could absorb the thin parts of this server. It cannot replace the
executable, governed, deterministic behavior the server exists to provide. The
best setup uses both.

> "Skills teach agents *how* to do things. MCP servers give agents the *ability*
> to do things." — Speakeasy, *Skills vs MCP, a false dichotomy*

> "Skills describe the workflow. MCP provides the runner." — Block's Goose team

Anthropic's own *Complete Guide to Building Skills for Claude* uses a kitchen
analogy that captures it exactly:

> "MCP provides the professional kitchen: access to tools, ingredients, and
> equipment. Skills provide the recipes: step-by-step instructions on how to
> create something valuable. Together, they enable users to accomplish complex
> tasks without needing to figure out every step themselves."

### Background: what each thing actually is

**MCP** (Model Context Protocol) is an open protocol, launched by Anthropic in
November 2024, that standardizes how LLM applications access context — *tools*,
*resources*, and *prompt templates* — over a client/server architecture. The
same server can be reused across many clients (Claude Desktop, MCP Inspector,
other vendors) and deployed remotely. (See the DeepLearning.AI course *MCP:
Build Rich-Context AI Apps with Anthropic*, taught by Anthropic's Elie Schoppik,
for the canonical walkthrough.) This repository is one such server: it exposes
Solr search, indexing, schema, and collection management as MCP tools.

**Agent Skills**, which Anthropic opened as a standard in December 2025, are —
per the *Complete Guide to Building Skills for Claude* — "a folder containing:
`SKILL.md` (required): Instructions in Markdown with YAML frontmatter;
`scripts/` (optional): Executable code; `references/` (optional): Documentation
loaded as needed; `assets/` (optional): Templates, fonts, icons used in output."
A skill is procedural knowledge — "when you hit this kind of problem, here's the
approach." Its defining mechanism is **progressive disclosure**, which Anthropic
describes as the "core design principle that makes Agent Skills flexible and
scalable":

1. **Metadata** — at startup only each skill's `name` and `description` sit in
   the context window.
2. **Instructions** — when a skill looks relevant, the agent reads the full
   `SKILL.md` into context.
3. **Resources** — `SKILL.md` can point to additional files (and executable
   code) loaded only when needed, so "the amount of context that can be bundled
   into a skill is effectively unbounded."

The guide stresses two further design principles that matter here:
**composability** — "Claude can load multiple skills simultaneously. Your skill
should work well alongside others, not assume it's the only capability
available" — and **portability** — "Skills work identically across Claude.ai,
Claude Code, and API." The net effect of progressive disclosure, in the guide's
words, is that it "minimizes token usage while maintaining specialized
expertise."

Anthropic itself frames the two as complementary, saying it will "explore how
Skills can complement Model Context Protocol (MCP) servers by teaching agents
more complex workflows that involve external tools and software." The Skills
guide spells out the division of labor for exactly the situation this repo is in
— a team that already ships a server:

> "MCP (Connectivity) … Connects Claude to your service … Provides real-time
> data access and tool invocation … *What Claude can do*. Skills (Knowledge) …
> Teaches Claude how to use your service effectively … Captures workflows and
> best practices … *How Claude should do it*."

### The strongest case for "just use a skill"

The argument is real and worth stating at full strength. Modern agents (Claude
Code, Goose) have code execution built in. Give one a skill that documents
Solr's HTTP API — the query syntax, the admin endpoints, the schema API — and it
can `curl` Solr directly. No server to host. And **many MCP servers genuinely
are thin wrappers** that a capable agent with code execution could replace.

For this server, a skill could plausibly absorb:

- **`search`** — building `q`, `fq`, `facet.field`, `start`, `rows` is exactly
  the kind of thing a model does well unaided.
- **`get-schema`, `list-collections`, `create-collection`** — near pass-throughs
  to Solr's admin API.
- General "how do I model and query this in Solr" guidance — precisely what
  skills are *for*.

If your world is "one developer, ad-hoc work, a Solr they fully control," a skill
covers a lot of the read path. Speakeasy concedes the same: the equivalence is
"roughly true for one developer."

### Where the equivalence breaks

"Roughly true for one developer" and "true for your organization" are different
claims, and the gap is where this server earns its keep. Several tools here
encode behavior that is **code, not knowledge** — an agent emitting raw HTTP
would have to re-derive it, imperfectly, on every call:

1. **Indexing resilience.** Documents are batched (1000 at a time) with a single
   commit, and on a failed batch the service retries documents individually to
   salvage the valid ones. A model orchestrating `curl` won't reliably reproduce
   that fallback in one deterministic step.
2. **Format parsing + hardening.** Nested-object flattening (`user.name` →
   `user_name`), CSV header sanitization, field-name rules, 10 MB size guards,
   and **XXE-hardened XML parsing**. The XML hardening especially is not
   something you want a model improvising per call.
3. **Metric aggregation.** `get-collection-stats` stitches together the Luke
   handler, the query response, and the Metrics API; normalizes shard names
   (`films_shard1_replica_n1` → `films`); and degrades gracefully on Solr 10,
   where `/admin/mbeans` was removed. That's institutional knowledge living in
   code.
4. **Schema field-type construction.** `add-field-types` builds SolrJ
   `FieldTypeDefinition` objects by hand because flat JSON can't be deserialized
   straight into the typed analyzer sub-objects. A skill hits the same wall with
   no escape hatch.
5. **Determinism, auth, typed contracts.** Every tool runs under
   `@PreAuthorize("isAuthenticated()")`, returns typed records, and behaves
   identically on every call.

And at organization scale, Speakeasy's governance points apply directly:

- **Auth.** In the skill-plus-sandbox model, the agent must hold raw Solr/OAuth
  credentials to write them into the code it runs — "it has to know your secrets
  to use them." An MCP server authenticates server-side; the agent makes a
  structured call and never sees the credential.
- **Observability.** Tool calls through the server are logged, rate-limitable,
  and auditable. A skill executing generated code is not centrally observable.
- **Centralized updates vs. distributed drift.** When Solr's API or your schema
  conventions change, you update one server and every agent gets the new behavior
  at once. With skills-plus-sandbox you depend on every developer's skill file
  being updated and the model generating correct code against the new contract —
  the same configuration-drift problem infrastructure-as-code was built to solve.

> "The sandbox gives you capability. MCP gives you capability with guardrails."

### But what about context cost? Isn't a skill cheaper?

This is the strongest *technical* argument for skills, and it's largely about
progressive disclosure: a traditional MCP client injects **every** connected
server's full tool definitions into context at startup, used or not. Connect
several servers and you spend tens of thousands of tokens before the user says
anything. A skill costs only its one-line description until invoked.

Two findings keep this from being a knockout:

1. **It's an artifact of older clients, and Anthropic has already addressed it.**
   In *Code execution with MCP*, Anthropic presents servers as code APIs (e.g.
   TypeScript files on disk) the agent reads **on demand** — the same
   progressive-disclosure idea applied to tools. In their example this cut tool
   loading from **150,000 tokens to 2,000 — a 98.7% reduction**, and one workflow
   used **78.5% fewer input tokens** overall. Cloudflare reports the same result
   under the name "Code Mode." Lazy/deferred tool loading collapses the upfront
   cost of an ~11-tool server toward a skill's frontmatter cost.
2. **Upfront cost isn't total cost — and the server often wins at runtime.** A
   skill `curl`-ing Solr pulls large raw JSON payloads into context and reasons
   over them on every call. An MCP tool returns a compact, *shaped* typed record
   (`SearchResponse`, `SolrMetrics`) — the parsing and aggregation happened in
   Java, off-context. For `get-collection-stats`, which folds three Solr
   endpoints into one record, the server can consume *less* total context than a
   skill doing the same work in-loop.

### A practical pattern: delegate MCP work to a subagent

You can have the best of both. Rather than the main agent calling tools directly
and absorbing verbose tool I/O, have it **delegate MCP interactions to a
subagent** that runs in its own context window, does the multi-step work, and
returns only a distilled summary. This is the same instinct as Anthropic's code
execution: "filtering data before it reaches the model" and "executing complex
logic in a single step." The raw payloads and call-by-call chatter stay
quarantined in a throwaway context.

It's especially worthwhile for this server's heavier workflows:

- **Bulk indexing** — a subagent drives the batch/retry loop across thousands of
  documents and hands back "indexed 4,812 of 4,815; 3 failures: …".
- **Stats / health sweeps** — `get-collection-stats` across many collections
  produces verbose metric trees; a subagent folds them into a one-paragraph
  verdict.
- **Schema introspection** — `get-schema` can be large; let a subagent read it
  and report only the fields relevant to the task.

The net effect: the determinism and safety of the MCP server, with a main-thread
footprint close to a skill's. (Anthropic notes the tradeoff — code/subagent
execution needs a sandbox with resource limits and monitoring, so weigh that
operational overhead.)

### Security considerations

Security is where the "just use a skill with code execution" argument is weakest,
and it's worth treating on its own because the two models have very different
attack surfaces.

**Credential exposure.** This is the sharpest difference. In the
skill-plus-sandbox model the agent must hold raw secrets — Solr credentials, the
OAuth token — to write them into the code it executes; as Speakeasy puts it, "it
has to know your secrets to use them." This server inverts that. In HTTP mode it
is **secured by default**: an OAuth2 resource-server filter chain authenticates
every MCP tool call as a JWT bearer token, and every `@McpTool` runs under
`@PreAuthorize("isAuthenticated()")`. The agent makes a structured call; the
server authenticates on its behalf; the credential never enters the model's
context where it could be logged, cached, or exfiltrated through a prompt
injection. (See [`docs/security/http.md`](security/http.md).)

**Constrained surface vs. arbitrary execution.** An MCP tool is a *narrow,
typed door* — the agent can only do the operations the server chose to expose. A
skill that `curl`s Solr from a sandbox is a *wide door*: the same code path that
queries Solr can hit any URL (SSRF), read the filesystem, or exfiltrate data.
Anthropic is explicit that this capability has a cost — running agent-generated
code "requires a secure execution environment with appropriate sandboxing,
resource limits, and monitoring," operational overhead "that direct tool calls
avoid."

**Input hardening lives in the server.** Several defenses here are code, and a
skill improvising HTTP calls would have to reinvent each one correctly every
time:

- **XXE-hardened XML parsing** (doctype declarations disallowed, external
  entities disabled) in the document creators — exactly the kind of parser
  vulnerability you do not want a model re-deriving per call.
- **10 MB size guards** on JSON/CSV/XML payloads, a basic DoS / resource-exhaustion
  bound.
- **Field-name sanitization** before anything reaches Solr.
- **XML wire format** (`XMLRequestWriter`) chosen partly to avoid the JavaBin
  codec's deep reflection.

**Transport-appropriate trust.** The server's trust model is documented and
spec-aligned: STDIO inherits trust from the OS user that launched the process and
runs with no network listener (no socket for a remote attacker), while HTTP
requires OAuth2 per the MCP Authorization spec. `SOLR_URL` is treated as
deployer-controlled config, never wired from a tool argument. (See
[`docs/security/stdio.md`](security/stdio.md).) A skill has no equivalent,
enforced trust boundary — its safety depends on the model behaving correctly each
run.

**Observability and blast radius.** Tool calls through the server are loggable,
rate-limitable, and auditable; a sandbox executing generated code is not centrally
observable. If something goes wrong, "every agent can run arbitrary code with raw
credentials" and "every agent can call a fixed set of authenticated, audited
tools" are very different incident-response stories.

**Shared risks — vet both.** Neither model is automatically safe. Skills are
shareable artifacts, so a malicious or compromised `SKILL.md` can carry injected
instructions; MCP servers carry their own supply-chain risk if you connect an
untrusted one. Both demand provenance checks, and tool/skill output should be
treated as untrusted content that may attempt prompt injection. The difference is
that a vetted MCP server gives you *one* place to enforce auth, validation, and
logging, rather than relying on every developer's skill file and every generated
script being correct.

### The decision framework

Adapting Speakeasy's heuristic to this project:

| Criterion              | Reach for a **Skill**                          | Reach for **this MCP server**                        |
|------------------------|------------------------------------------------|------------------------------------------------------|
| What you're providing  | A pattern or process ("how to query Solr well") | Access to a live service (search, index, schema ops) |
| Content nature         | Static, curated by your team                   | Real-time data and side effects                      |
| Auth needed            | No                                             | Yes — handled server-side, agent never sees secrets  |
| Monitoring / audit     | Not centrally observable                       | Logged, rate-limitable, auditable                    |
| Determinism            | Sampled from the model each call               | Same input → same output                             |
| Reuse                  | Only agents that load skills                   | Any MCP client (Claude Desktop, Inspector, others)   |
| Infrastructure         | None (just a file)                             | A hosted process with dependencies and error handling |

### Bottom line

The "skill *or* MCP" framing is, as Speakeasy puts it, a **false dichotomy**.
And for a project that already ships a working server, Anthropic's Skills guide
points the same way:

> "If you already have a working MCP server, you've done the hard part. Skills
> are the knowledge layer on top — capturing the workflows and best practices you
> already know, so Claude can apply them consistently."

For this repository:

- **Keep the MCP server** for deterministic, security-sensitive, multi-step
  operations (indexing, stats aggregation, schema mutation) and for the many
  non-Claude clients that consume it — it's an Apache incubating project meant to
  be used by *any* MCP client, not just skill-aware agents.
- **Add a thin Solr skill** that teaches an agent *when and how* to use these
  tools well — good query and faceting patterns, when to reindex, how to read
  the stats — falling back to raw HTTP only for ad-hoc reads the server doesn't
  expose.
- **Delegate the heavy turns to a subagent** to keep the main context lean.

The skill makes the agent better at *using* Solr; the server makes the dangerous
and repetitive parts *safe and repeatable*. Replacing the latter with the former
trades away determinism, auth, and observability for flexibility you mostly don't
want on a write path.

## Sources

- Anthropic — [Equipping agents for the real world with Agent Skills](https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills)
- Anthropic — [Code execution with MCP: building more efficient AI agents](https://www.anthropic.com/engineering/code-execution-with-mcp)
- Anthropic — [The Complete Guide to Building Skills for Claude](https://resources.anthropic.com/hubfs/The-Complete-Guide-to-Building-Skill-for-Claude.pdf) (PDF)
- DeepLearning.AI — [MCP: Build Rich-Context AI Apps with Anthropic](https://learn.deeplearning.ai/courses/mcp-build-rich-context-ai-apps-with-anthropic/) (taught by Elie Schoppik)
- Speakeasy — [Skills vs MCP, a false dichotomy](https://www.speakeasy.com/blog/skills-vs-mcp)
- Cloudflare — [Code Mode](https://blog.cloudflare.com/code-mode/) (referenced by Anthropic)
