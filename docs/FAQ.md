# Frequently Asked Questions

## LLMs already know how to talk to Solr. Could this MCP server be replaced by a Solr "skill" for agents?

**Short answer: no — but they pair well.** A skill could absorb the thin parts of
this server; it can't replace the deterministic, governed, secured behavior the
server exists to provide. Skills and MCP aren't rivals — they're complementary
layers, and the strongest setup uses both.

> "MCP provides the professional kitchen: access to tools, ingredients, and
> equipment. Skills provide the recipes." — Anthropic, *Complete Guide to
> Building Skills for Claude*

**The split** (Anthropic's own framing): MCP = *connectivity*, what Claude **can**
do (tools/resources over client/server). Skills = *knowledge*, how Claude
**should** do it (a `SKILL.md` folder loaded by progressive disclosure). This
repo is an MCP server exposing Solr search, indexing, schema, and collection
tools.

### What a skill *could* take over

The thin, read-mostly path where the value is just "the model knows Solr":
`search` (building `q`/`fq`/`facet`), `get-schema`, `list-collections`,
`create-collection`, and general "how to query Solr well" guidance. For one
developer against a Solr they control, a skill plus `curl` covers a lot — many
MCP servers really are thin wrappers.

### What it *can't*

This server encodes behavior that is **code, not knowledge** — an agent emitting
raw HTTP would re-derive it imperfectly every call:

- **Indexing resilience** — 1000-doc batches, single commit, per-doc retry on a
  failed batch to salvage valid docs.
- **Format parsing + hardening** — nested-object flattening, field sanitization,
  10 MB guards, and **XXE-hardened XML parsing**.
- **Metric aggregation** — `get-collection-stats` folds Luke + Metrics APIs,
  normalizes shard names, and degrades gracefully on Solr 10.
- **Determinism, auth, typed contracts** — every tool runs under
  `@PreAuthorize` and returns the same typed record every time.

And at org scale: **auth** (the server authenticates server-side; the agent never
holds raw secrets), **observability** (calls are logged and auditable), and
**no config drift** (update one server vs. every dev's skill file).

> "The sandbox gives you capability. MCP gives you capability with guardrails."
> — Speakeasy

### Context cost & security — the two common objections

- **"A skill is cheaper on context."** True upfront — but lazy/deferred tool
  loading closes the gap (Anthropic's *Code execution with MCP* cut tool loading
  ~98.7%, 150K→2K tokens), and the server often wins at *runtime* by returning
  compact typed records instead of raw JSON the model must parse. You can also
  **delegate heavy turns to a subagent**, keeping verbose tool I/O off the main
  thread.
- **"Security is a wash."** It isn't. A sandboxed skill needs raw credentials and
  can hit any URL (SSRF/exfiltration); a tool is a narrow, audited door. This
  server adds OAuth2 (HTTP), a documented STDIO/HTTP trust model, XXE hardening,
  and size limits. Vet both for supply-chain/prompt-injection risk — but a server
  gives you *one* place to enforce it.

### Decision framework

| Criterion         | Reach for a **Skill**          | Reach for **this MCP server**                   |
|-------------------|--------------------------------|-------------------------------------------------|
| Providing         | A pattern / process            | Access to a live service                        |
| Content           | Static, team-curated           | Real-time data and side effects                 |
| Auth              | None                           | Yes — server-side, agent never sees secrets     |
| Audit             | Not centrally observable       | Logged, rate-limitable, auditable               |
| Determinism       | Sampled each call              | Same input → same output                        |
| Reuse             | Skill-aware agents only        | Any MCP client (Claude Desktop, Inspector, …)   |

### Bottom line

> "If you already have a working MCP server, you've done the hard part. Skills are
> the knowledge layer on top." — Anthropic, *Complete Guide to Building Skills*

**Keep the server** for deterministic, security-sensitive, multi-step operations
and for non-Claude clients (it's an Apache incubating project for *any* MCP
client). **Add a thin Solr skill** for query/faceting/reindex know-how.
**Delegate heavy turns to a subagent** to keep context lean. The skill makes the
agent better at *using* Solr; the server makes the dangerous parts *safe and
repeatable*.

## Sources

- Anthropic — [Equipping agents for the real world with Agent Skills](https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills)
- Anthropic — [Code execution with MCP](https://www.anthropic.com/engineering/code-execution-with-mcp)
- Anthropic — [The Complete Guide to Building Skills for Claude](https://resources.anthropic.com/hubfs/The-Complete-Guide-to-Building-Skill-for-Claude.pdf) (PDF)
- DeepLearning.AI — [MCP: Build Rich-Context AI Apps with Anthropic](https://learn.deeplearning.ai/courses/mcp-build-rich-context-ai-apps-with-anthropic/)
- Speakeasy — [Skills vs MCP, a false dichotomy](https://www.speakeasy.com/blog/skills-vs-mcp)
- Cloudflare — [Code Mode](https://blog.cloudflare.com/code-mode/)
