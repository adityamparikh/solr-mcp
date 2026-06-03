# Frequently Asked Questions

## LLMs already know how to talk to Solr. Could this MCP server be replaced by a Solr "skill" for agents?

Partly — but not entirely, and the two are better understood as **complements**
than substitutes. This question gets at the *raison d'être* of the project, so
it's worth answering carefully.

### Short answer

A skill (instructions + knowledge loaded into an agent's context) can replace
the *thin* parts of this server — the places where the value really is just "the
model knows Solr." It cannot replace the parts that are **executable, versioned,
deterministic behavior** with security and error handling baked in. The strongest
setup uses both: the MCP server for safe, repeatable operations, and a skill that
teaches an agent *when and how* to use it well.

### Skill vs. MCP server, at a glance

|                  | **Skill**                                  | **This MCP server**                                  |
|------------------|--------------------------------------------|------------------------------------------------------|
| Distributes      | Instructions + knowledge                   | Executable, versioned behavior                       |
| Runs where       | Inside the agent's loop (the model itself) | A real process with auth, dependencies, error handling |
| Determinism      | Sampled from the model on every call       | Same input → same output                             |
| Reuse            | Only by agents that load skills            | Any MCP client (Claude Desktop, MCP Inspector, other vendors) |
| Security         | The model must get it right each call      | Hardcoded (XXE guards, size limits, OAuth2)          |

### What a skill genuinely could absorb

These are the parts where "the LLM knows Solr" is most of the value:

- **`search`** — building a query, choosing facet parameters, pagination. A model
  can write `q`, `fq`, `facet.field`, `start`, and `rows` against the HTTP API
  directly.
- **`get-schema`, `list-collections`, `create-collection`** — near pass-throughs
  to the Solr admin API.
- General "how do I query Solr" guidance — exactly what skills are for.

If your deployment is "an agent with shell/HTTP access talking to a Solr it fully
controls," a skill covers a lot of the read path.

### What does *not* survive translation into a skill

Several tools encode behavior that is **code, not knowledge** — an agent emitting
raw HTTP calls would have to re-derive it (often imperfectly) on every call:

1. **Indexing resilience.** Documents are batched (1000 at a time) with a single
   commit, and on a failed batch the service retries documents individually to
   salvage the valid ones. A model orchestrating `curl` calls won't reliably
   reproduce that fallback in one deterministic step.
2. **Format parsing + sanitization.** Nested-object flattening
   (`user.name` → `user_name`), CSV header sanitization, field-name rules, 10 MB
   size guards, and **XXE-hardened XML parsing**. The XML hardening in particular
   is exactly the kind of thing you do not want a model improvising per call.
3. **Metric aggregation.** `get-collection-stats` stitches together the Luke
   handler, the query response, and the Metrics API, normalizes shard names
   (`films_shard1_replica_n1` → `films`), and degrades gracefully on Solr 10
   where `/admin/mbeans` was removed. That's institutional knowledge living in
   code.
4. **Schema field-type construction.** `add-field-types` builds SolrJ
   `FieldTypeDefinition` objects by hand because flat JSON input can't be
   deserialized straight into the typed analyzer sub-objects. A skill would hit
   the same wall with no escape hatch.
5. **Determinism, auth, and typed contracts.** Every tool runs under
   `@PreAuthorize("isAuthenticated()")`, returns typed records, and behaves
   identically on every call. A skill's behavior is sampled from the model each
   time — fine for exploration, risky for an indexing or schema-mutation pipeline.

### The product boundary

The MCP server is also a **product boundary**, not just a convenience for one
agent. It is an Apache incubating project meant to be consumed by *any* MCP client
— Claude Desktop, the MCP Inspector, and tools from other vendors. A skill is
specific to skill-aware agents. That alone is reason for the server to exist
independently of how capable the underlying model is.

### Recommended approach: use both

They are complements:

- **Keep the MCP server** for deterministic, security-sensitive, multi-step
  operations (indexing, stats aggregation, schema mutation) and for non-Claude
  clients.
- **Add a thin Solr skill** that teaches an agent *when and how* to use these
  tools well — good query/faceting patterns, when to reindex, how to interpret
  stats — falling back to raw HTTP only for ad-hoc reads the server doesn't
  expose.

The skill makes the agent better at *using* Solr; the server makes certain
operations *safe and repeatable*. Replacing the latter with the former trades
away determinism and security for flexibility you mostly don't want on a write
path.
