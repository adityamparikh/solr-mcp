# Keycloak Authentication Guide for Solr MCP Server

This guide covers setting up [Keycloak](https://www.keycloak.org/) as an OAuth2/OpenID Connect identity provider for the Solr MCP Server running in HTTP mode.

## Table of Contents

- [Overview](#overview)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Keycloak Setup](#keycloak-setup)
  - [Running Keycloak](#running-keycloak)
  - [Creating a Realm](#creating-a-realm)
  - [Creating Clients](#creating-clients)
    - [Audience mapper (required)](#audience-mapper-required)
  - [Creating Test Users](#creating-test-users)
- [Spring Boot Configuration](#spring-boot-configuration)
- [Running the Server](#running-the-server)
- [Testing Authentication](#testing-authentication)
- [User Management Options](#user-management-options)
  - [Manual User Creation](#manual-user-creation)
  - [Identity Brokering (GitHub, Google, etc.)](#identity-brokering-github-google-etc)
  - [Self-Registration](#self-registration)
  - [REST API](#rest-api)
  - [JSON Import](#json-import)
  - [Custom User Storage SPI](#custom-user-storage-spi)
- [GitHub Identity Provider Setup](#github-identity-provider-setup)
- [Role-Based Access Control (Optional)](#role-based-access-control-optional)
- [Troubleshooting](#troubleshooting)

## Overview

The Solr MCP Server supports OAuth2 authentication via JWT tokens. Keycloak acts as the authorization server, issuing tokens that the MCP server validates. This enables:

- Centralized user management
- Single Sign-On (SSO) across multiple applications
- Integration with external identity providers (GitHub, Google, LDAP, etc.)
- Fine-grained role-based access control

### Authentication Flow

```
User                    MCP Client            Keycloak              Solr MCP Server
  │                        │                     │                        │
  │─── Request Access ────►│                     │                        │
  │                        │─── Auth Request ───►│                        │
  │◄────────────────────── Login Page ◄──────────│                        │
  │─── Credentials ───────────────────────────────►                       │
  │                        │◄─── JWT Token ──────│                        │
  │                        │─── API Request + Token ────────────────────►│
  │                        │                     │      (validates JWT)   │
  │                        │◄─────────────────────────── Response ────────│
  │◄─── Result ────────────│                     │                        │
```

## Prerequisites

- Docker (for running Keycloak)
- Java 25 (for Solr MCP Server)
- Gradle (for building the server)

## Quick Start

Every command below is runnable as-is. Step 2 is **not optional**: the MCP server
resolves the issuer's OpenID configuration eagerly while the Spring context is built,
so if the realm does not exist yet, step 3 aborts startup rather than failing later on
a request.

```bash
# 1. Start Keycloak and wait for it to accept connections
docker run -d --name keycloak \
  -p 8180:8080 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin \
  -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:26.0 start-dev

until curl -sf http://localhost:8180/realms/master/.well-known/openid-configuration \
  > /dev/null; do sleep 2; done
```

```bash
# 2. Configure the realm, client and test user (equivalent to the console steps below)
kcadm() { docker exec keycloak /opt/keycloak/bin/kcadm.sh "$@"; }

kcadm config credentials --server http://localhost:8080 \
  --realm master --user admin --password admin

kcadm create realms -s realm=solr-mcp -s enabled=true

# Public client. directAccessGrantsEnabled is what makes the password grant in
# "Testing Authentication" work.
CLIENT_ID=$(kcadm create clients -r solr-mcp -i \
  -s clientId=solr-mcp-client -s enabled=true -s publicClient=true \
  -s directAccessGrantsEnabled=true -s standardFlowEnabled=true \
  -s 'redirectUris=["http://localhost:6274/*"]' \
  -s 'webOrigins=["http://localhost:6274"]')

# Audience mapper. The MCP server validates that "aud" matches its canonical
# resource URI, and Keycloak does not populate that on its own — without this
# mapper every tool call is rejected with 401 "The aud claim is not valid".
kcadm create clients/"$CLIENT_ID"/protocol-mappers/models -r solr-mcp \
  -s name=mcp-audience -s protocol=openid-connect \
  -s protocolMapper=oidc-audience-mapper \
  -s 'config."included.custom.audience"=http://localhost:8080/mcp' \
  -s 'config."access.token.claim"=true' -s 'config."id.token.claim"=false'

# Test user. firstName and lastName are required by Keycloak's default user
# profile — omit them and token requests fail with "Account is not fully set up".
USER_ID=$(kcadm create users -r solr-mcp -i \
  -s username=testuser -s enabled=true \
  -s email=test@example.com -s emailVerified=true \
  -s firstName=Test -s lastName=User)

kcadm set-password -r solr-mcp --username testuser --new-password testpassword
```

```bash
# 3. Run Solr MCP Server with security enabled.
#    The http profile enables Spring Boot's Docker Compose support, so this also
#    starts the Solr and ZooKeeper services from compose.yaml.
export PROFILES=http
export HTTP_SECURITY_ENABLED=true
export OAUTH2_ISSUER_URI=http://localhost:8180/realms/solr-mcp
./gradlew bootRun
```

```bash
# 4. Verify: an unauthenticated tool call is denied, an authenticated one succeeds
CALL='{"jsonrpc":"2.0","method":"tools/call","id":1,
       "params":{"name":"list-collections","arguments":{}}}'
HDRS=(-H "Content-Type: application/json" -H "Accept: application/json, text/event-stream")

curl -s -X POST http://localhost:8080/mcp "${HDRS[@]}" -d "$CALL"
# -> {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Access Denied"}],"isError":true}}

TOKEN=$(curl -s -X POST \
  "http://localhost:8180/realms/solr-mcp/protocol/openid-connect/token" \
  -d client_id=solr-mcp-client -d username=testuser \
  -d password=testpassword -d grant_type=password | jq -r .access_token)

curl -s -X POST http://localhost:8080/mcp -H "Authorization: Bearer $TOKEN" \
  "${HDRS[@]}" -d "$CALL"
# -> {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"[\"books\",...]"}],"isError":false}}
```

> **Note:** `tools/list` answers without a token by design — `/mcp` is permitted at the
> HTTP layer and authorization is enforced per tool via `@PreAuthorize`. Use a
> `tools/call` as above to confirm security is actually active.

## Keycloak Setup

### Running Keycloak

**Development Mode (Docker):**

```bash
docker run -d --name keycloak \
  -p 8180:8080 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin \
  -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:26.0 start-dev
```

Access the admin console at `http://localhost:8180` and log in with `admin/admin`.

**Production Mode:**

For production deployments, use a proper database backend and TLS:

```bash
docker run -d --name keycloak \
  -p 8443:8443 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin \
  -e KC_BOOTSTRAP_ADMIN_PASSWORD=<secure-password> \
  -e KC_DB=postgres \
  -e KC_DB_URL=jdbc:postgresql://db-host:5432/keycloak \
  -e KC_DB_USERNAME=keycloak \
  -e KC_DB_PASSWORD=<db-password> \
  -e KC_HOSTNAME=keycloak.example.com \
  quay.io/keycloak/keycloak:26.0 start
```

### Creating a Realm

1. Log into Keycloak Admin Console: `http://localhost:8180/admin`
2. Click the dropdown in the top-left (shows "master")
3. Click **Create realm**
4. Realm name: `solr-mcp`
5. Click **Create**

### Creating Clients

You need at least one client for applications to authenticate against.

#### Resource Server Client (for the MCP Server)

1. Navigate to **Clients** → **Create client**
2. Configure:
   - Client ID: `solr-mcp-server`
   - Client type: `OpenID Connect`
3. Click **Next**
4. Client authentication: **ON** (confidential client)
5. Authentication flow: Enable **Service accounts roles**
6. Click **Next** → **Save**

#### Public Client (for testing/MCP Inspector)

1. Navigate to **Clients** → **Create client**
2. Configure:
   - Client ID: `solr-mcp-client`
   - Client type: `OpenID Connect`
3. Click **Next**
4. Client authentication: **OFF** (public client)
5. Authentication flow: keep **Standard flow** enabled, and keep **Direct access
   grants** enabled — the password-grant `curl` in
   [Testing Authentication](#testing-authentication) needs it
6. Click **Next**
7. Configure access settings:
   - Valid redirect URIs: `http://localhost:6274/*`, `http://localhost:*`
   - Web origins: `*` or `http://localhost:6274`
8. Click **Save**

#### Audience mapper (required)

The MCP server validates that the token's `aud` claim matches its canonical resource
URI, per [RFC 8707](https://www.rfc-editor.org/rfc/rfc8707.html) and the MCP
Authorization specification. Keycloak does not populate that claim on its own, so
without this mapper **every tool call fails** with:

```
401 WWW-Authenticate: Bearer error="invalid_token",
    error_description="An error occurred while attempting to decode the Jwt:
                       The aud claim is not valid"
```

1. Open the `solr-mcp-client` client → **Client scopes** tab
2. Click the dedicated scope (`solr-mcp-client-dedicated`) → **Add mapper** → **By
   configuration** → **Audience**
3. Configure:
   - Name: `mcp-audience`
   - Included Custom Audience: the MCP server's resource URI, e.g.
     `http://localhost:8080/mcp`
   - Add to access token: **ON**
4. Click **Save**

See [HTTP transport security — Per-IdP setup for the audience claim](http.md) for the
Auth0 and Okta equivalents.

### Creating Test Users

1. Navigate to **Users** → **Add user**
2. Configure:
   - Username: `testuser`
   - Email: `test@example.com`
   - Email verified: **ON**
   - First name: `Test`
   - Last name: `User`
3. Click **Create**
4. Go to the **Credentials** tab
5. Click **Set password**
6. Enter password and disable **Temporary**
7. Click **Save**

> **First and last name are required.** Keycloak's default user profile marks them
> mandatory, and a user missing either one cannot obtain a token at all — the token
> endpoint returns `{"error":"invalid_grant","error_description":"Account is not fully
> set up"}`, which does not obviously point at the missing name fields.
>
> **Step 6 matters for the same reason.** Leaving **Temporary** ON attaches an
> `UPDATE_PASSWORD` required action, which fails the token request with that same
> message. The [Troubleshooting](#troubleshooting) section shows how to tell the two
> apart.

## Spring Boot Configuration

The Solr MCP Server is pre-configured to work with any OAuth2/OIDC provider. Update `application-http.properties`:

```properties
# Security toggle - set to true to enable OAuth2 authentication
http.security.enabled=${HTTP_SECURITY_ENABLED:true}

# Keycloak OAuth2 Configuration
# Format: https://<keycloak-host>/realms/<realm-name>
spring.security.oauth2.resourceserver.jwt.issuer-uri=${OAUTH2_ISSUER_URI:http://localhost:8180/realms/solr-mcp}
```

No code changes are required—the existing `McpServerConfiguration` handles JWT validation automatically by discovering Keycloak's JWKS endpoint from the issuer URI.

## Running the Server

**With Security Enabled:**

```bash
export PROFILES=http
export HTTP_SECURITY_ENABLED=true
export OAUTH2_ISSUER_URI=http://localhost:8180/realms/solr-mcp
./gradlew bootRun
```

**Without Security (Development Only):**

```bash
export PROFILES=http
export HTTP_SECURITY_ENABLED=false
./gradlew bootRun
```

## Testing Authentication

### Obtain a Token

**Using Resource Owner Password Grant (for testing):**

```bash
curl -X POST "http://localhost:8180/realms/solr-mcp/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=solr-mcp-client" \
  -d "username=testuser" \
  -d "password=testpassword" \
  -d "grant_type=password"
```

Response:

```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expires_in": 300,
  "refresh_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "token_type": "Bearer"
}
```

### Call the MCP Server

```bash
# Store token in variable
TOKEN=$(curl -s -X POST "http://localhost:8180/realms/solr-mcp/protocol/openid-connect/token" \
  -d "client_id=solr-mcp-client" \
  -d "username=testuser" \
  -d "password=testpassword" \
  -d "grant_type=password" | jq -r '.access_token')

# Call MCP endpoint
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'
```

## User Management Options

Keycloak provides multiple ways to manage users beyond manual creation.

### Manual User Creation

As described above, create users via **Users** → **Add user** in the admin console.


### Identity Brokering (GitHub, Google, etc.)

Allow users to authenticate via external identity providers. See [GitHub Identity Provider Setup](#github-identity-provider-setup) for a detailed example.

Supported providers include:
- GitHub
- Google
- Microsoft/Azure AD
- Facebook
- Twitter/X
- Any SAML 2.0 or OIDC provider

### Self-Registration

Allow users to create their own accounts:

1. Navigate to **Realm settings** → **Login** tab
2. Enable **User registration**
3. Optionally enable:
   - Email verification
   - Terms and conditions
   - reCAPTCHA

### REST API

Programmatically create users:

```bash
# Get admin token
TOKEN=$(curl -s -X POST "http://localhost:8180/realms/master/protocol/openid-connect/token" \
  -d "client_id=admin-cli" \
  -d "username=admin" \
  -d "password=admin" \
  -d "grant_type=password" | jq -r '.access_token')

# Create user
curl -X POST "http://localhost:8180/admin/realms/solr-mcp/users" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "newuser",
    "email": "newuser@example.com",
    "enabled": true,
    "emailVerified": true,
    "credentials": [{
      "type": "password",
      "value": "temppassword",
      "temporary": true
    }]
  }'
```

> `"temporary": true` is the right default when provisioning a real user — it forces a
> password change on first login. It also attaches an `UPDATE_PASSWORD` required action,
> so this account cannot obtain a token via the password grant until that is resolved
> through the browser login flow. Use `"temporary": false` for accounts you intend to
> drive from scripts or tests, and populate `firstName`/`lastName` for the same reason.

### JSON Import

Bulk import users during realm setup:

```json
{
  "realm": "solr-mcp",
  "users": [
    {
      "username": "user1",
      "email": "user1@example.com",
      "enabled": true,
      "credentials": [{"type": "password", "value": "pass123"}],
      "realmRoles": ["user", "solr-query"]
    },
    {
      "username": "user2",
      "email": "user2@example.com",
      "enabled": true,
      "credentials": [{"type": "password", "value": "pass456"}],
      "realmRoles": ["admin"]
    }
  ]
}
```

Import via CLI:

```bash
/opt/keycloak/bin/kc.sh import --file realm-export.json
```

### Custom User Storage SPI

For databases or custom backends, implement Keycloak's User Storage SPI to authenticate against your existing user database without migration.

## GitHub Identity Provider Setup

### Step 1: Create GitHub OAuth App

1. Go to GitHub → **Settings** → **Developer settings** → **OAuth Apps** → **New OAuth App**

   Direct link: https://github.com/settings/applications/new

2. Fill in the form:

| Field | Value |
|-------|-------|
| Application name | `Solr MCP Server` |
| Homepage URL | `http://localhost:8180` |
| Authorization callback URL | `http://localhost:8180/realms/solr-mcp/broker/github/endpoint` |

> **Note:** The callback URL format is: `https://<keycloak-host>/realms/<realm-name>/broker/github/endpoint`

3. Click **Register application**
4. Note the **Client ID** and generate a **Client Secret**

### Step 2: Configure Keycloak

1. Log into Keycloak Admin Console
2. Select your realm (`solr-mcp`)
3. Navigate to **Identity Providers** → **Add provider** → **GitHub**
4. Configure:

| Field | Value |
|-------|-------|
| Client ID | `<from GitHub>` |
| Client Secret | `<from GitHub>` |
| Default Scopes | `user:email` (optional) |

5. Click **Save**

### Step 3: Test GitHub Login

1. Open: `http://localhost:8180/realms/solr-mcp/account`
2. Click the **GitHub** button
3. Authorize on GitHub
4. You're logged into Keycloak with your GitHub account

### Optional: Map GitHub Data

Add mappers to import GitHub profile data:

1. Navigate to **Identity Providers** → **GitHub** → **Mappers**
2. Click **Add mapper**
3. Example configurations:

| Name | Mapper Type | Claim | User Attribute |
|------|-------------|-------|----------------|
| GitHub Username | Attribute Importer | `login` | `github_username` |
| GitHub Avatar | Attribute Importer | `avatar_url` | `avatar` |
| GitHub Email | Attribute Importer | `email` | `email` |

### Optional: Auto-Assign Roles

Automatically assign roles to GitHub users:

1. Navigate to **Identity Providers** → **GitHub** → **Mappers**
2. Click **Add mapper**
3. Configure:
   - Mapper Type: `Hardcoded Role`
   - Role: Select a role (e.g., `solr-query`)

## Role-Based Access Control (Optional)

To use Keycloak roles with Spring Security's `@PreAuthorize` annotations, add a JWT converter.

### Create Roles in Keycloak

1. Navigate to **Realm roles** → **Create role**
2. Create roles like `admin`, `solr-query`, `solr-admin`
3. Assign roles to users via **Users** → select user → **Role mappings**

### Configure Spring Security

Add to `McpServerConfiguration.java`:

```java
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

@Bean
@ConditionalOnProperty(name = "http.security.enabled", havingValue = "true", matchIfMissing = true)
public JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
    // Keycloak stores realm roles in realm_access.roles
    grantedAuthoritiesConverter.setAuthoritiesClaimName("realm_access.roles");
    grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");

    JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
    jwtConverter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
    return jwtConverter;
}
```

Wire it into the security filter chain:

```java
@Bean
@ConditionalOnProperty(name = "http.security.enabled", havingValue = "true", matchIfMissing = true)
SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
    return http
        .authorizeHttpRequests(auth -> {
            auth.requestMatchers("/actuator").permitAll();
            auth.requestMatchers("/actuator/*").permitAll();
            auth.requestMatchers("/mcp").permitAll();
            auth.anyRequest().authenticated();
        })
        .oauth2ResourceServer(oauth2 -> oauth2
            .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
        )
        .with(McpServerOAuth2Configurer.mcpServerOAuth2(), (mcpAuthorization) -> {
            mcpAuthorization.authorizationServer(issuerUrl);
        })
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .csrf(CsrfConfigurer::disable)
        .build();
}
```

### Use Role Annotations

```java
@PreAuthorize("hasRole('admin')")
public String deleteCollection(String name) { ... }

@PreAuthorize("hasRole('solr-query')")
public String executeQuery(String collection, String query) { ... }

@PreAuthorize("hasAnyRole('solr-query', 'solr-admin')")
public String getSchema(String collection) { ... }
```

### Keycloak Role Locations

| Location | Claim Path | Use Case |
|----------|------------|----------|
| Realm roles | `realm_access.roles` | Global roles across all clients |
| Client roles | `resource_access.<client-id>.roles` | Roles specific to a client |

## Troubleshooting

### Common Issues

**The server exits with code 1 and prints nothing:**

```
> Task :bootRun FAILED
> Process 'command '.../bin/java'' finished with non-zero exit value 1
```

Almost always the realm does not exist yet — step 2 of the
[Quick Start](#quick-start) was skipped or only partly applied. Confirm with:

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  http://localhost:8180/realms/solr-mcp/.well-known/openid-configuration
# 200 = realm exists; 404 = create it
```

The MCP server builds its `NimbusJwtDecoder` eagerly while the Spring context is
created, so an issuer that is *set but unresolvable* fails at startup. Note that an
issuer left **empty** starts fine (the filter chain still returns 401/403) — only a
configured-but-wrong issuer aborts startup.

If you see no log output at all alongside the failure, force the logging
configuration to surface the real stack trace:

```bash
LOGGING_CONFIG=classpath:logback-spring.xml PROFILES=http ./gradlew bootRun
```

**"Unable to resolve the Configuration with the provided Issuer":**

- Keycloak must be running and accessible from the MCP server
- The realm in `OAUTH2_ISSUER_URI` must already exist (see above)
- Check the issuer URL: `http://localhost:8180/realms/solr-mcp/.well-known/openid-configuration`

**401 with `"The aud claim is not valid"`:**

The token has no audience matching the MCP server's resource URI. Add the
[audience mapper](#audience-mapper-required) to the client, then request a fresh
token — existing tokens keep the old claims. Verify with:

```bash
echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq .aud
# must contain the MCP server resource URI, e.g. "http://localhost:8080/mcp"
```

**`{"error":"invalid_grant","error_description":"Account is not fully set up"}`:**

The account is not in a state Keycloak will issue a token for. The password grant has
no browser leg, so there is nowhere to render the "complete your profile" or "update
your password" page — Keycloak rejects the whole grant instead of prompting. The same
account signs in fine through a browser flow, which is why this looks like it only
breaks `curl`.

Two different causes produce this identical message:

1. **A required user-profile field is blank** — usually **first name** or **last name**,
   both of which are `required` in Keycloak's default user profile. Fill them in under
   **Users** → select user → **Details**.
2. **The account has a pending required action** — most often `UPDATE_PASSWORD`, added
   automatically whenever a password is set with **Temporary** ON.

Check both at once:

```bash
ADMIN=$(curl -s -X POST "http://localhost:8180/realms/master/protocol/openid-connect/token" \
  -d "client_id=admin-cli" -d "username=admin" -d "password=admin" \
  -d "grant_type=password" | jq -r '.access_token')

curl -s -H "Authorization: Bearer $ADMIN" \
  "http://localhost:8180/admin/realms/solr-mcp/users?username=testuser&exact=true" \
  | jq -c '.[] | {username, firstName, lastName, emailVerified, requiredActions}'
```

A ready-to-use account has both names populated and an empty `requiredActions`:

```json
{"username":"testuser","firstName":"Test","lastName":"User","emailVerified":true,"requiredActions":[]}
```

Required actions and what they mean:

| Required action | Usual cause | Fix |
|-----------------|-------------|-----|
| `UPDATE_PASSWORD` | Password was set with **Temporary** ON | Re-set the password with **Temporary** OFF |
| `VERIFY_EMAIL` | Realm has email verification enabled | Set **Email verified** ON for the user |
| `UPDATE_PROFILE` | A required profile field is blank | Fill in the missing fields |
| `CONFIGURE_TOTP` | OTP required by the realm or a policy | Enroll OTP via the browser flow, or drop the requirement |

Clear pending actions from the console (**Users** → select user → **Details** →
**Required user actions** → remove → **Save**), or over the REST API:

```bash
USER_ID=$(curl -s -H "Authorization: Bearer $ADMIN" \
  "http://localhost:8180/admin/realms/solr-mcp/users?username=testuser&exact=true" | jq -r '.[0].id')

curl -X PUT "http://localhost:8180/admin/realms/solr-mcp/users/$USER_ID" \
  -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" \
  -d '{"requiredActions":[]}'
```

Clearing an action does not change the password — the credential you already set stays
valid. Do this for test accounts only; real users should resolve the action through the
browser login flow.

**A tool call returns `"Access Denied"` instead of 401:**

Expected when no token is sent. `/mcp` is permitted at the HTTP layer and
authorization is enforced per tool by `@PreAuthorize`, so an anonymous `tools/call`
comes back as a JSON-RPC error with `isError: true` rather than an HTTP 401. An
anonymous `tools/list` succeeding is also expected.

**"Invalid token" or 401 Unauthorized (other causes):**

- Verify `OAUTH2_ISSUER_URI` matches your Keycloak realm URL exactly
- Check that the token hasn't expired (default lifetime is 5 minutes)

**CORS errors with MCP Inspector:**

- Ensure Web origins are configured in your Keycloak client
- Add `http://localhost:6274` to Web origins

**Token doesn't contain roles:**

- Verify the user has roles assigned in Keycloak
- Check the token contents at https://jwt.io
- Ensure you're reading the correct claim path (`realm_access.roles`)

### Useful Commands

**Inspect a JWT token:**

```bash
# Decode token (without verification)
echo $TOKEN | cut -d'.' -f2 | base64 -d 2>/dev/null | jq
```

**Check Keycloak OpenID configuration:**

```bash
curl http://localhost:8180/realms/solr-mcp/.well-known/openid-configuration | jq
```

**Check whether an account can obtain a token:**

```bash
curl -s -H "Authorization: Bearer $ADMIN" \
  "http://localhost:8180/admin/realms/solr-mcp/users?username=testuser&exact=true" \
  | jq -c '.[] | {firstName, lastName, requiredActions}'
# names populated + requiredActions [] = good to go
```

**View Keycloak logs:**

```bash
docker logs -f keycloak
```

## References

- [Keycloak Documentation](https://www.keycloak.org/documentation)
- [Spring Security OAuth2 Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html)
- [MCP Specification](https://spec.modelcontextprotocol.io/)
