# FlowOps M1 API Contract — Auth + Multi-Tenancy

**Status:** FROZEN. Single source of truth for parallel backend + frontend implementation.
**Base URL:** `http://localhost:8080` (`NEXT_PUBLIC_API_URL`)
**Version:** 1.0 · 2026-08-24

---

## 0. Global conventions

| Rule | Value |
|---|---|
| Content type | `application/json; charset=UTF-8` on every request with a body and every response with a body |
| JSON casing | **camelCase**, always. No snake_case anywhere on the wire. |
| IDs | UUID v4, lowercase canonical 36-char string (`"9f2c1e10-4b7a-4f3e-9c11-2b8d5a6e7f01"`). Java `UUID.randomUUID()`, Postgres column type `uuid`. |
| Timestamps | ISO-8601 UTC, milliseconds, `Z` suffix: `"2026-08-24T10:15:30.123Z"`. Java `Instant`, Postgres `timestamptz`. Set `spring.jackson.serialization.write-dates-as-timestamps: false`. |
| Enums | UPPER_SNAKE strings. Never integers. |
| `204` responses | No body at all. Do not send `{}`. |
| Unknown request fields | **Rejected** with `400 MALFORMED_REQUEST`. Set `spring.jackson.deserialization.fail-on-unknown-properties: true`. This is a security control: a client that tries to smuggle `"role"` or `"organizationId"` into a body gets an error instead of a silent ignore. |
| Auth responses | All responses from `/api/auth/*` and the switch endpoint carry `Cache-Control: no-store`. |
| Trailing slashes | Not supported. Exact paths only. |

### CORS (already implemented in `WebConfig.java` — do not change)

`allowedOrigins` = explicit allowlist from `flowops.cors.allowed-origins`, `allowCredentials(true)`, `allowedHeaders("*")`, methods include `PATCH`.

**Do not** replace `allowedOrigins` with `allowedOriginPatterns("*")` — a wildcard origin is illegal with `allowCredentials(true)` and would break the refresh cookie.

Frontend **must** send credentials on every request (`axios: withCredentials: true`, `fetch: credentials: "include"`).

---

## 1. Token & session design (decided)

### 1.1 Access token — JWT, in memory, `Authorization: Bearer`

* **Algorithm:** HS256, secret from `JWT_SECRET` (min 32 chars, already in `.env.example`).
* **TTL:** 900 s (15 min). Property `flowops.auth.access-token-ttl: 15m`.
* **Transit:** `Authorization: Bearer <jwt>` header. Never a cookie, never a query param.
* **Frontend storage:** **in-memory only** (Zustand store, no `persist` middleware). Never `localStorage`, never `sessionStorage`, never a JS-readable cookie. Lost on reload — recovered by the silent refresh in §6.1.
* **Clock skew:** accept 30 s leeway on `exp`/`iat`.

Payload claims — exact names:

```json
{
  "iss": "flowops",
  "aud": "flowops-api",
  "sub": "9f2c1e10-4b7a-4f3e-9c11-2b8d5a6e7f01",
  "jti": "d41b0c88-6a5e-4c2a-8f77-5e1a3b9c2d40",
  "iat": 1787654130,
  "exp": 1787655030,
  "tokenUse": "access",
  "sid": "31c7a9de-0b44-4e58-9a7c-6d2f8e1b0c73",
  "orgId": "5b8e2f41-9c3d-4a76-b1e8-7f0a2c4d6e89",
  "role": "OWNER",
  "email": "ada@example.com"
}
```

Validation on every request: signature, `exp`, `iss == "flowops"`, `aud == "flowops-api"`, `tokenUse == "access"`. Reject otherwise. `email` is a convenience claim only — **never** an authorization input.

* `sub` — user id. The authenticated principal.
* `sid` — **session id** (FK to `auth_sessions`). Lets the switch-org endpoint mutate session state without needing the refresh cookie, so the cookie stays narrowly scoped.
* `orgId` — **the tenant context. The only source of truth for authorization.**
* `role` — caller's role in `orgId`, one of `OWNER | ADMIN | MEMBER | VIEWER`.

The JWT filter does **no database access**. Everything needed for authorization is in the claims.

**Accepted tradeoff (document it, do not "fix" it with a per-request DB lookup):** because access tokens are stateless, a logged-out or role-changed or org-switched token remains accepted until it expires — a window of **at most 15 minutes**. Refresh tokens and sessions are revoked immediately, so the blast radius is bounded by the 15-minute TTL.

### 1.2 Refresh token — opaque, HttpOnly cookie

* **Format:** 256 bits from `SecureRandom`, base64url-encoded without padding (43 chars). **Not a JWT.**
* **Storage at rest:** only the **SHA-256 hash** of the token is stored (`refresh_tokens.token_hash`). The plaintext exists solely in the cookie. A database dump does not yield usable refresh tokens.
* **TTL:** 604800 s (7 days). Property `flowops.auth.refresh-token-ttl: 7d`.
* **Transit:** `Set-Cookie` / `Cookie` only. **The refresh token never appears in a request body, a response body, a URL, or a log line.** There is no code path where the frontend can read it — which is the point.

Cookie attributes:

| Attribute | Value |
|---|---|
| Name | `flowops_refresh` |
| `Path` | `/api/auth` |
| `HttpOnly` | yes, always |
| `Max-Age` | `604800` |
| `Secure` | `flowops.auth.refresh-cookie.secure` — **`false`** in dev, **`true`** everywhere else |
| `SameSite` | `flowops.auth.refresh-cookie.same-site` — **`Lax`** in dev, **`None`** in prod |
| `Domain` | omitted (host-only cookie) |

Exact headers:

```http
# dev (localhost:3000 -> localhost:8080)
Set-Cookie: flowops_refresh=<token>; Max-Age=604800; Path=/api/auth; HttpOnly; SameSite=Lax

# prod (app.example.com -> api.example.com, or cross-site hosts)
Set-Cookie: flowops_refresh=<token>; Max-Age=604800; Path=/api/auth; HttpOnly; Secure; SameSite=None

# clearing (logout) — Path must match exactly or the cookie will not be removed
Set-Cookie: flowops_refresh=; Max-Age=0; Path=/api/auth; HttpOnly; SameSite=Lax
```

`SameSite=None` requires `Secure=true`; the backend must fail fast at startup if `same-site=None` and `secure=false`.

**Why `Lax` works in dev:** SameSite is computed from the registrable domain, and **ports are not part of the site**. `localhost:3000` → `localhost:8080` is same-site (though cross-origin), so a `Lax` cookie is sent on the XHR. Production hosts may be cross-site, hence `None` there. **Why `Secure=false` in dev:** Chrome and Firefox permit `Secure` cookies over `http://localhost`, but Safari does not — a `Secure` dev default would silently break Safari.

**Why this split (justification).** The access token is the only credential JS ever touches, and it is (a) short-lived and (b) never written to persistent storage, so an XSS payload gets ≤15 minutes of access and cannot establish persistence. The long-lived credential is `HttpOnly`, so XSS cannot exfiltrate it at all. This is strictly stronger than putting the refresh token in `localStorage`, and strictly simpler than a full server-side session store plus CSRF-token endpoint. Only two endpoints read cookies, and both are CSRF-hardened by §1.4.

### 1.3 Rotation + reuse detection

Every successful `POST /api/auth/refresh`:

1. Hash the presented token, look it up.
2. Reject unless it exists, `revoked_at IS NULL`, `used_at IS NULL`, `expires_at > now()`, and its session has `revoked_at IS NULL`.
3. Mark the presented token `used_at = now()`, `revoked_at = now()`.
4. Mint a **new** refresh token in the **same session** (`sid` unchanged) and set the new cookie.

**Reuse detection:** if a token is presented that exists but is already `used_at`/`revoked_at`, treat it as theft: revoke **every session and refresh token belonging to that user**, and return `401 REFRESH_TOKEN_INVALID`. The user must log in again everywhere.

Because reuse detection is destructive, the frontend **must single-flight refresh** — one shared in-flight promise per tab, so concurrent 401s from parallel requests produce exactly one refresh call (§6.2). Two tabs racing is tolerated: the loser gets a 401 and re-runs boot refresh.

### 1.4 CSRF defense for the two cookie-reading endpoints

`POST /api/auth/refresh` and `POST /api/auth/logout` are declared `consumes = "application/json"` and **must** be called with `Content-Type: application/json` and body `{}`.

`Content-Type: application/json` is not a CORS-simple value, so any cross-site attempt triggers a **preflight**, which fails for origins outside the allowlist — the browser never sends the real request. An HTML `<form>` (which can only produce simple content types) gets `415 UNSUPPORTED_MEDIA_TYPE`. No CSRF token endpoint, no double-submit cookie, no extra machinery.

Additionally, a successful CSRF would still leak nothing: the new access token is in a response body that CORS forbids a foreign origin from reading.

Spring Security's own CSRF filter is **disabled** (`csrf.disable()`) — it is redundant here and its cookie/repository model conflicts with a bearer-token API.

---

## 2. The tenant-context rule (CRITICAL)

> "Never trust client-provided organization IDs. Always derive authorization context from the authenticated user."

Concretely, and non-negotiably:

1. **Every authorization decision reads `orgId` from the verified JWT.** Nothing else is an acceptable input.
2. **No request body field in M1 is named `organizationId`.** Not one. Combined with `fail-on-unknown-properties: true`, sending one is a `400`.
3. **No query parameter selects an organization.** Ever.
4. **There is deliberately no `GET /api/organizations/{id}`.** Org reads go through `/api/organizations/current`, which resolves from the token. Adding a by-id read endpoint later requires a membership check inside the same transaction as the read.
5. **Exactly one endpoint accepts an org id in the URL:** `POST /api/organizations/{organizationId}/switch`. There it is a *selector*, not an authorization input — the server verifies `(userId from JWT, organizationId)` has a row in `organization_members` **before** issuing a token for it. If no such row exists, the response is `404` (see §2.1). A selector that is validated against the principal's own memberships before use does not violate the rule; reading an org id and *acting on it* without that check does.
6. **Every repository query is org-scoped.** Data-access methods take the org id as a parameter derived from the principal, e.g. `findByIdAndOrganizationId(id, orgId)` — never `findById(id)` followed by an in-memory org comparison, and never a filter applied in the service layer after an unscoped fetch.

Recommended plumbing: a `JwtAuthenticationFilter` builds a `FlowOpsPrincipal(userId, sessionId, organizationId, role, email)` and puts it in the `SecurityContext`. A `@CurrentTenant` argument resolver or `AuthenticatedUser.current()` helper is the **only** way controllers obtain an org id. Backend engineer: if a controller method signature contains an org id that did not come from the principal (outside the switch endpoint), that is a defect.

### 2.1 Non-member and nonexistent orgs are indistinguishable

`POST /api/organizations/{organizationId}/switch` returns **`404 ORGANIZATION_NOT_FOUND`** both when the org does not exist and when the caller is not a member. Returning `403` for the second case would confirm the existence of another tenant's organization to an attacker enumerating UUIDs. There is no `403` on this endpoint and no `NOT_A_MEMBER` error code.

### 2.2 Determining the current organization

* **Register** — the newly created org.
* **Login** — the org from the caller's **earliest-joined** membership (`ORDER BY joined_at ASC, organization_id ASC` — the tiebreaker makes it deterministic). For a fresh user that is the org created at registration.
* **Every authenticated request** — the `orgId` claim. No lookup, no negotiation, no header.
* **Switch** — `POST /api/organizations/{organizationId}/switch` verifies membership, writes `auth_sessions.organization_id = :organizationId`, and returns a **new access token** carrying the new `orgId` and the caller's role in it. **No `Set-Cookie` is returned** — the refresh cookie is untouched.
* **Refresh** — reads `auth_sessions.organization_id`, then **re-verifies membership** (the caller may have been removed since). Still a member → mint with that org. No longer a member → fall back to the earliest-joined remaining membership and update the session. Zero memberships → `403 NO_ORGANIZATION_CONTEXT` (unreachable in M1: registration always creates an org and M1 has no leave/remove endpoint, but the rule is specified so both implementations agree).

Because the switched org lives in the **session row** rather than in the cookie, it survives a page reload: boot refresh reads the session and returns the org the user last switched to. This is the reason for the `sid` claim.

**Roles** are `OWNER | ADMIN | MEMBER | VIEWER`, ordered `OWNER > ADMIN > MEMBER > VIEWER`. Exactly one `OWNER` exists per org in M1 (its creator). Role checks compare rank, so "requires ADMIN" means ADMIN or OWNER.

---

## 3. Error envelope — ONE shape for every error

Every non-2xx response from every endpoint, without exception — validation, 401, 403, 404, 405, 409, 415, 429, 500 — has this body:

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Request validation failed.",
    "status": 400,
    "timestamp": "2026-08-24T10:15:30.123Z",
    "path": "/api/auth/register",
    "requestId": "4f8a1c22e9b74d61",
    "fieldErrors": [
      { "field": "email", "message": "Enter a valid email address." },
      { "field": "password", "message": "Password must be at least 8 characters." }
    ]
  }
}
```

| Field | Type | Notes |
|---|---|---|
| `error.code` | string | Stable UPPER_SNAKE enum from §3.1. **Branch on this, never on `message`.** |
| `error.message` | string | Human-readable, safe to show. Never contains a stack trace, SQL, class name, email address, token, or password. |
| `error.status` | number | Mirrors the HTTP status. |
| `error.timestamp` | string | ISO-8601 UTC ms. |
| `error.path` | string | Request path, no query string. |
| `error.requestId` | string | 16 lowercase hex chars. Also returned as the `X-Request-Id` response header on **all** responses. Logged server-side with the full exception; quote it in bug reports. |
| `error.fieldErrors` | array \| **null** | **Key is always present.** An array only when `code == "VALIDATION_ERROR"`; `null` for every other code. |
| `fieldErrors[].field` | string | The **camelCase JSON field name** (`fullName`, not `full_name` or `registerRequest.fullName`). Backend must strip Bean Validation's object prefix. |
| `fieldErrors[].message` | string | One message per violation. Multiple entries per field are allowed; order is not guaranteed. |

Implementation: a single `@RestControllerAdvice` (`GlobalExceptionHandler`) plus a custom `AuthenticationEntryPoint` and `AccessDeniedHandler` so that Spring Security's own 401/403 paths emit this envelope too — **the default Spring Boot error body must never reach the client.** Also override `server.error.whitelabel.enabled: false` and map `/error`.

TypeScript type (shared, frontend):

```ts
export type ErrorCode =
  | "VALIDATION_ERROR" | "MALFORMED_REQUEST" | "UNSUPPORTED_MEDIA_TYPE"
  | "AUTHENTICATION_REQUIRED" | "INVALID_CREDENTIALS" | "TOKEN_EXPIRED"
  | "TOKEN_INVALID" | "REFRESH_TOKEN_INVALID"
  | "FORBIDDEN_ROLE" | "NO_ORGANIZATION_CONTEXT"
  | "ORGANIZATION_NOT_FOUND" | "NOT_FOUND" | "METHOD_NOT_ALLOWED"
  | "EMAIL_ALREADY_REGISTERED" | "RATE_LIMITED" | "INTERNAL_ERROR";

export interface FieldError { field: string; message: string }

export interface ApiError {
  error: {
    code: ErrorCode;
    message: string;
    status: number;
    timestamp: string;
    path: string;
    requestId: string;
    fieldErrors: FieldError[] | null;
  };
}
```

### 3.1 Error code catalogue (exhaustive for M1)

| Code | HTTP | Raised when |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Bean Validation failed. `fieldErrors` populated. |
| `MALFORMED_REQUEST` | 400 | Unparseable JSON, wrong JSON type, missing body, **or an unknown field**. `fieldErrors` is `null`. |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Missing/incorrect `Content-Type` on a `consumes`-restricted endpoint. |
| `AUTHENTICATION_REQUIRED` | 401 | No `Authorization` header on a protected endpoint. |
| `INVALID_CREDENTIALS` | 401 | Login: unknown email **or** wrong password. **Identical response for both.** |
| `TOKEN_EXPIRED` | 401 | Access token signature valid, `exp` passed. **The frontend's refresh trigger.** |
| `TOKEN_INVALID` | 401 | Bad signature, malformed, wrong `iss`/`aud`, `tokenUse != "access"`, or subject no longer exists. |
| `REFRESH_TOKEN_INVALID` | 401 | Refresh cookie absent, unknown, expired, revoked, reused, or its session is revoked. **Terminal — never retry.** |
| `FORBIDDEN_ROLE` | 403 | Authenticated, org context valid, role rank too low. |
| `NO_ORGANIZATION_CONTEXT` | 403 | Authenticated user has zero memberships. |
| `ORGANIZATION_NOT_FOUND` | 404 | Switch target does not exist **or** caller is not a member. |
| `NOT_FOUND` | 404 | Unknown route or unknown resource. |
| `METHOD_NOT_ALLOWED` | 405 | Known path, wrong verb. |
| `EMAIL_ALREADY_REGISTERED` | 409 | Register with an email already in `users`. |
| `RATE_LIMITED` | 429 | Throttle exceeded. Response includes `Retry-After: <seconds>`. |
| `INTERNAL_ERROR` | 500 | Anything unhandled. Generic message; details only in the server log under `requestId`. |

### 3.2 Enumeration posture

`INVALID_CREDENTIALS` is byte-identical for unknown email and wrong password. When the email is unknown, the backend **still performs a BCrypt comparison against a fixed dummy hash** so response timing does not distinguish the cases.

`EMAIL_ALREADY_REGISTERED` (409) on register does disclose that an email is registered. This is a **deliberate, accepted** tradeoff: the alternative (always 201 + an email-based confirmation flow) requires mail infrastructure that M1 does not have, and would be fake functionality. Revisit when email verification lands.

---

## 4. Resource shapes

These four shapes are used verbatim in every response. Field order is irrelevant; names and nullability are not.

### 4.1 `User`

```json
{
  "id": "9f2c1e10-4b7a-4f3e-9c11-2b8d5a6e7f01",
  "email": "ada@example.com",
  "fullName": "Ada Lovelace",
  "avatarUrl": null,
  "createdAt": "2026-08-24T10:15:30.123Z",
  "updatedAt": "2026-08-24T10:15:30.123Z"
}
```

* `email` — always the normalized (trimmed, lowercased) form.
* `avatarUrl` — `string | null`. **Always `null` in M1**; no upload exists. Present now so the shared type does not change when M5 adds uploads; render initials from `fullName` as the fallback.
* **`passwordHash` must never appear in any response, log line, exception message, `toString()`, or OpenAPI schema.** Annotate the entity field `@JsonIgnore` and exclude it from Lombok/record `toString`. Never construct a response DTO from the entity by reflection.

### 4.2 `Organization`

```json
{
  "id": "5b8e2f41-9c3d-4a76-b1e8-7f0a2c4d6e89",
  "name": "Acme Inc",
  "slug": "acme-inc",
  "createdAt": "2026-08-24T10:15:30.123Z",
  "updatedAt": "2026-08-24T10:15:30.123Z"
}
```

* `name` — as the user typed it (trimmed). **Not unique** — two unrelated tenants may both be "Acme Inc".
* `slug` — server-derived, **globally unique**, `^[a-z0-9]+(-[a-z0-9]+)*$`. Algorithm: lowercase → NFKD-normalize and strip diacritics → replace every run of non-`[a-z0-9]` with `-` → trim leading/trailing `-` → truncate to 80 chars → if empty, use `"org"`. On uniqueness collision append `-2`, then `-3`, … until free. Clients **never** send a slug and must not attempt to predict it.

### 4.3 `Membership`

The org-switcher payload — denormalized on purpose so the switcher needs no second request.

```json
{
  "organizationId": "5b8e2f41-9c3d-4a76-b1e8-7f0a2c4d6e89",
  "organizationName": "Acme Inc",
  "organizationSlug": "acme-inc",
  "role": "OWNER",
  "joinedAt": "2026-08-24T10:15:30.123Z"
}
```

`memberships` arrays are always sorted `joinedAt ASC, organizationId ASC`.

### 4.4 `OrganizationMember`

```json
{
  "userId": "9f2c1e10-4b7a-4f3e-9c11-2b8d5a6e7f01",
  "email": "ada@example.com",
  "fullName": "Ada Lovelace",
  "avatarUrl": null,
  "role": "OWNER",
  "joinedAt": "2026-08-24T10:15:30.123Z"
}
```

### 4.5 `SessionResponse` and `AuthResponse`

`SessionResponse` — the identity + tenant snapshot:

```json
{
  "user": { "...User..." },
  "currentOrganization": { "...Organization..." },
  "currentRole": "OWNER",
  "memberships": [ { "...Membership..." } ]
}
```

`AuthResponse` — `SessionResponse` **plus** the three token fields:

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI5ZjJjMWUxMCJ9.abc123",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "user": { "...User..." },
  "currentOrganization": { "...Organization..." },
  "currentRole": "OWNER",
  "memberships": [ { "...Membership..." } ]
}
```

* `tokenType` — literally `"Bearer"`, always.
* `expiresIn` — access-token lifetime in **seconds** (900). Not a timestamp, not ms.
* `currentRole` — caller's role in `currentOrganization`. Redundant with the matching `memberships` entry, and included anyway so RBAC-gated components never scan the array on every render.
* `currentOrganization` and `currentRole` are **non-null** in every `AuthResponse`/`SessionResponse`. If no org context can be established the request fails with `403 NO_ORGANIZATION_CONTEXT` instead of returning nulls.
* There is **no `refreshToken` field.** If one appears in a response body, that is a defect.

`AuthResponse` is returned by register, login, refresh, and switch-org — one shape, so the frontend has exactly one "hydrate auth state" function.

```ts
export type Role = "OWNER" | "ADMIN" | "MEMBER" | "VIEWER";

export interface User { id: string; email: string; fullName: string; avatarUrl: string | null; createdAt: string; updatedAt: string }
export interface Organization { id: string; name: string; slug: string; createdAt: string; updatedAt: string }
export interface Membership { organizationId: string; organizationName: string; organizationSlug: string; role: Role; joinedAt: string }
export interface OrganizationMember { userId: string; email: string; fullName: string; avatarUrl: string | null; role: Role; joinedAt: string }

export interface SessionResponse { user: User; currentOrganization: Organization; currentRole: Role; memberships: Membership[] }
export interface AuthResponse extends SessionResponse { accessToken: string; tokenType: "Bearer"; expiresIn: number }
```

---

## 5. Endpoints

Legend — **Auth:** `PUBLIC` (no credential) · `COOKIE` (refresh cookie) · `BEARER` (access token) · `BEARER + role`.

### 5.1 `POST /api/auth/register` — PUBLIC

Creates `User` + `Organization` + `OrganizationMember(role=OWNER)` **in one transaction**, then logs the user in. If any step fails, nothing is persisted.

Request (`Content-Type: application/json` required):

```json
{
  "email": "ada@example.com",
  "password": "correct-horse-9",
  "fullName": "Ada Lovelace",
  "organizationName": "Acme Inc"
}
```

There is no `role` field and no `organizationId` field. The role is hardcoded `OWNER` server-side. Sending either → `400 MALFORMED_REQUEST`.

**`201 Created`** — body `AuthResponse`, plus `Set-Cookie: flowops_refresh=...`. No `Location` header. `memberships` has exactly one entry; `currentRole` is `"OWNER"`.

| Status | Code | Cause |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Field rules in §7 violated |
| 400 | `MALFORMED_REQUEST` | Bad JSON or unknown field |
| 409 | `EMAIL_ALREADY_REGISTERED` | Email exists (case-insensitive) |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Wrong `Content-Type` |
| 429 | `RATE_LIMITED` | Throttle (§8) |
| 500 | `INTERNAL_ERROR` | — |

Concurrency: rely on the `UNIQUE` index on `users.email`; catch the constraint violation and translate to 409. Do not rely on a check-then-insert.

### 5.2 `POST /api/auth/login` — PUBLIC

```json
{ "email": "ada@example.com", "password": "correct-horse-9" }
```

Creates a **new** `auth_sessions` row (a new `sid`) and a first refresh token. Existing sessions on other devices are untouched.

**`200 OK`** — `AuthResponse` + `Set-Cookie`. Current org = earliest-joined membership (§2.2).

| Status | Code | Cause |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Missing/blank/oversized fields. **Login only checks presence + max length — it must never reject a credential for failing the password *complexity* rule**, or it would leak that legacy passwords differ in form. |
| 400 | `MALFORMED_REQUEST` | Bad JSON / unknown field |
| 401 | `INVALID_CREDENTIALS` | Unknown email or wrong password — identical either way |
| 403 | `NO_ORGANIZATION_CONTEXT` | Zero memberships (unreachable in M1) |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Wrong `Content-Type` |
| 429 | `RATE_LIMITED` | Throttle (§8) |

### 5.3 `POST /api/auth/refresh` — COOKIE

Rotates the refresh token and mints a new access token. **`Content-Type: application/json` and body `{}` are mandatory** (§1.4). No `Authorization` header needed — and any header sent is ignored, so an expired access token is fine.

Request: `{}`

**`200 OK`** — full `AuthResponse` (not just a token) + a rotated `Set-Cookie`.

Returning the whole `AuthResponse` is deliberate: app boot becomes **one** round trip instead of `refresh` + `me`, and the user/org/membership data is refreshed from the database on every reload rather than going stale.

| Status | Code | Cause |
|---|---|---|
| 400 | `MALFORMED_REQUEST` | Body is not valid JSON |
| 401 | `REFRESH_TOKEN_INVALID` | Cookie missing, unknown, expired, revoked, **reused** (→ all user sessions revoked), or session revoked. Response clears the cookie. **Terminal: never retry, go to `/login`.** |
| 403 | `NO_ORGANIZATION_CONTEXT` | Zero memberships remain |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Wrong `Content-Type` (this is the CSRF block) |
| 429 | `RATE_LIMITED` | Throttle (§8) |

### 5.4 `POST /api/auth/logout` — COOKIE (bearer optional)

Revokes the session and **all** refresh tokens in it, then clears the cookie. `Content-Type: application/json`, body `{}`.

Session resolution: if a valid `Authorization` bearer is present, use its `sid`; otherwise resolve the session from the refresh cookie.

**`204 No Content`** — no body, plus the cookie-clearing `Set-Cookie`.

**Logout is idempotent and never fails.** Missing cookie, expired token, garbage token, already-logged-out — **always `204`**. It must never return 401, because a user clicking "log out" on a stale tab has to end up logged out. The only possible failures:

| Status | Code | Cause |
|---|---|---|
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Wrong `Content-Type` |
| 500 | `INTERNAL_ERROR` | — |

Reminder: an already-issued access token stays valid for up to 15 min after logout (§1.1). The frontend must discard it from memory immediately.

### 5.5 `GET /api/auth/me` — BEARER

Reads the current identity and tenant context **fresh from the database**, keyed by `sub`/`orgId` in the token. Use it to re-sync after a role change; use `refresh` (not `me`) on boot.

No request body. No query params.

**`200 OK`** — `SessionResponse` (no token fields).

| Status | Code | Cause |
|---|---|---|
| 401 | `AUTHENTICATION_REQUIRED` | No `Authorization` header |
| 401 | `TOKEN_EXPIRED` | Expired access token |
| 401 | `TOKEN_INVALID` | Bad signature / claims / deleted user |
| 403 | `NO_ORGANIZATION_CONTEXT` | Zero memberships |
| 403 | `FORBIDDEN_ROLE` | `orgId` claim no longer matches a membership (user removed from the org mid-token) |

### 5.6 `GET /api/organizations` — BEARER

Every organization the **authenticated user** belongs to. Not a global list — an admin of one tenant cannot see another tenant here or anywhere. Powers the org switcher.

**`200 OK`**

```json
{
  "memberships": [
    { "organizationId": "5b8e2f41-9c3d-4a76-b1e8-7f0a2c4d6e89", "organizationName": "Acme Inc", "organizationSlug": "acme-inc", "role": "OWNER", "joinedAt": "2026-08-24T10:15:30.123Z" },
    { "organizationId": "7a1d3e52-0f4c-4b81-9d22-3e6b8f0a1c45", "organizationName": "Globex", "organizationSlug": "globex", "role": "MEMBER", "joinedAt": "2026-08-25T09:00:00.000Z" }
  ]
}
```

Always an object with a `memberships` key — never a bare top-level array (a top-level array is awkward to extend and historically a JSON-hijacking footgun). Never empty in M1. Errors: 401 family.

### 5.7 `GET /api/organizations/current` — BEARER

The org identified by the `orgId` claim. Takes no id from the client.

**`200 OK`**

```json
{
  "organization": { "...Organization..." },
  "role": "OWNER",
  "memberCount": 1
}
```

`role` is the caller's role. `memberCount` is a live `COUNT(*)` over that org's members. Errors: 401 family, `403 FORBIDDEN_ROLE` if the claim no longer matches a membership.

### 5.8 `PATCH /api/organizations/current` — BEARER + `ADMIN` (ADMIN or OWNER)

Renames the current org. Path carries no id; the target is the token's `orgId`.

```json
{ "name": "Vantis" }
```

`slug` is **immutable** — renaming does not regenerate it, so existing links keep working. Sending `slug` → `400 MALFORMED_REQUEST`.

**`200 OK`** — same body shape as §5.7, with the updated organization.

| Status | Code | Cause |
|---|---|---|
| 400 | `VALIDATION_ERROR` | `name` violates §7 |
| 400 | `MALFORMED_REQUEST` | Bad JSON / unknown field |
| 401 | 401 family | — |
| 403 | `FORBIDDEN_ROLE` | Caller is `MEMBER` or `VIEWER` |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Wrong `Content-Type` |

This is the reference RBAC endpoint — use it for the frontend's 403 handling and for the backend's role-check test.

### 5.9 `POST /api/organizations` — BEARER

Creates an additional organization with the caller as `OWNER`. Same atomic guarantee as register (org + membership in one transaction).

```json
{ "organizationName": "Globex" }
```

Field is `organizationName`, matching register — not `name` — so one Zod schema fragment serves both forms.

**`201 Created`**

```json
{
  "organization": { "...Organization..." },
  "role": "OWNER"
}
```

**Creating an org does not switch you into it.** The access token is unchanged and no cookie is set; `orgId` still points at the previous org. To enter the new org, call §5.10. Silent context switches as a side effect of a create are a bug factory. The frontend should refetch §5.6 afterwards.

| Status | Code | Cause |
|---|---|---|
| 400 | `VALIDATION_ERROR` / `MALFORMED_REQUEST` | — |
| 401 | 401 family | — |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | — |
| 429 | `RATE_LIMITED` | Throttle (§8) |

### 5.10 `POST /api/organizations/{organizationId}/switch` — BEARER

The **only** endpoint taking an org id from the client, and it validates that id against the caller's own memberships before using it (§2, §2.1).

Server sequence, in order:
1. Read `userId` and `sid` from the verified JWT.
2. `SELECT ... FROM organization_members WHERE user_id = :userId AND organization_id = :organizationId` — **not found → `404 ORGANIZATION_NOT_FOUND`.**
3. `UPDATE auth_sessions SET organization_id = :organizationId WHERE id = :sid`.
4. Mint a new access token with the new `orgId` and the role read in step 2.

Request: **no body.** `Content-Type` is not required (nothing is parsed). Path param `organizationId` must be a well-formed UUID.

**`200 OK`** — full `AuthResponse` with the new `accessToken`, `currentOrganization`, and `currentRole`. **No `Set-Cookie`** — the refresh cookie is not rotated; the session row now carries the new org, so a later refresh returns the switched org.

| Status | Code | Cause |
|---|---|---|
| 400 | `MALFORMED_REQUEST` | `organizationId` is not a valid UUID |
| 401 | 401 family | — |
| 404 | `ORGANIZATION_NOT_FOUND` | Org absent **or** caller not a member — indistinguishable by design |

Switching to the org you are already in is a valid no-op that still returns a fresh token.

### 5.11 `GET /api/organizations/current/members` — BEARER

Members of the current org, resolved from `orgId`. Readable by **any** role including `VIEWER` (a member list within your own tenant is not privileged; mutating it is, and that lands in M5).

**`200 OK`**

```json
{
  "members": [
    { "userId": "9f2c1e10-4b7a-4f3e-9c11-2b8d5a6e7f01", "email": "ada@example.com", "fullName": "Ada Lovelace", "avatarUrl": null, "role": "OWNER", "joinedAt": "2026-08-24T10:15:30.123Z" }
  ]
}
```

Sorted `joinedAt ASC, userId ASC`. No pagination in M1 (org sizes are trivial); when it arrives it will be `?page=&size=` with a wrapper object — hence the object envelope now. Errors: 401 family, `403 FORBIDDEN_ROLE` if the claim no longer matches a membership.

### 5.12 Route table

| Method | Path | Auth |
|---|---|---|
| POST | `/api/auth/register` | PUBLIC |
| POST | `/api/auth/login` | PUBLIC |
| POST | `/api/auth/refresh` | COOKIE |
| POST | `/api/auth/logout` | COOKIE (bearer optional) |
| GET | `/api/auth/me` | BEARER |
| GET | `/api/organizations` | BEARER |
| POST | `/api/organizations` | BEARER |
| GET | `/api/organizations/current` | BEARER |
| PATCH | `/api/organizations/current` | BEARER + ADMIN |
| GET | `/api/organizations/current/members` | BEARER |
| POST | `/api/organizations/{organizationId}/switch` | BEARER |

Spring Security matchers — `permitAll`: `/api/auth/register`, `/api/auth/login`, `/api/auth/refresh`, `/api/auth/logout`, `/health/**`, `/actuator/health/**`, `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`, and `OPTIONS /**` (preflight must never be authenticated). Everything else `authenticated()`. Session policy `STATELESS`. `/api/organizations/current` must be registered **before** any `{id}` pattern so `current` is never parsed as a UUID — with only the `/switch` suffix pattern present there is no collision, but keep the ordering rule if more routes are added.

---

## 6. Frontend integration (normative)

### 6.1 Boot sequence

Access tokens live in memory only, so a reload has no token. On mount of the authenticated layout:

1. `POST /api/auth/refresh` with `{}`, `withCredentials: true`.
2. **200** → hydrate the store from `AuthResponse`, render the app.
3. **401** → unauthenticated; clear store, redirect to `/login`. This is the normal cold-start path for a logged-out visitor and must not surface an error toast.

Render a splash/skeleton until this settles — never flash the login screen at an authenticated user.

### 6.2 Axios instance

```ts
const api = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_URL,
  withCredentials: true,
  headers: { "Content-Type": "application/json" },
});
```

* Request interceptor: attach `Authorization: Bearer ${accessToken}` when the in-memory token exists.
* Response interceptor: on `401` with `error.code === "TOKEN_EXPIRED"` → **single-flight** refresh (module-level `let refreshPromise: Promise<AuthResponse> | null`, reused by concurrent callers, cleared in `finally`) → retry the original request **once** (guard with a `_retried` flag).
* **Never** refresh-retry `/api/auth/refresh`, `/api/auth/login`, or `/api/auth/logout`.
* `REFRESH_TOKEN_INVALID`, `AUTHENTICATION_REQUIRED`, and `TOKEN_INVALID` are terminal: clear store, redirect to `/login`. No retry.
* Optional: proactively refresh at `expiresIn - 60` seconds to avoid user-visible 401s.

### 6.3 Do not gate routes in `middleware.ts`

**Trap:** in dev the refresh cookie is host-only for `localhost`, and **cookies ignore ports**, so Next middleware on `:3000` *can* see `flowops_refresh` — but in production the API is a different host and it *cannot*. A middleware cookie check therefore works locally and silently fails in prod.

Gate with a **client-side `AuthGuard`** driven by the §6.1 boot refresh. The backend is the only authority; the guard is UX, not security.

### 6.4 Org switcher

Render from `memberships`. On selection: `POST /api/organizations/{id}/switch` → replace the entire auth store from the returned `AuthResponse` → **invalidate every TanStack Query cache** (`queryClient.clear()`, or at minimum `invalidateQueries()` across all org-scoped keys). Stale data from the previous tenant must never render under the new one. Include the org id in query keys from M2 onward.

### 6.5 Field-error mapping

On `400 VALIDATION_ERROR`, map `error.fieldErrors[].field` onto form fields by exact camelCase name (`email`, `password`, `fullName`, `organizationName`, `name`). Show `error.message` as a form-level error for any field name you do not recognize, so a backend rule never silently swallows a submit.

---

## 7. Validation rules — backend and frontend must agree exactly

Two shared regexes, used **verbatim** on both sides. Do not substitute Bean Validation's `@Email` or Zod's `.email()`: their edge-case behaviour differs, which would let one side accept what the other rejects.

```
EMAIL     ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$
PASSWORD  ^(?=.*[A-Za-z])(?=.*\d).{8,72}$
```

Length semantics match: both Java `String.length()` and JS `.length` count UTF-16 code units, so a surrogate-pair emoji counts as 2 on both sides. `.` excludes line terminators in both by default.

### 7.1 Rules

| Field | Used by | Required | Normalization | Length | Pattern | Message on failure |
|---|---|---|---|---|---|---|
| `email` | register, login | yes | `strip()` then `toLowerCase(Locale.ROOT)` | 1–255 (post-trim) | `EMAIL` | "Enter a valid email address." |
| `password` | register | yes | **none** — never trimmed | 8–72 chars **and** ≤ 72 UTF-8 bytes | `PASSWORD` | "Password must be 8–72 characters and include at least one letter and one number." |
| `password` | login | yes | none | 1–72 | **none** | "Password is required." |
| `fullName` | register | yes | `strip()` | 2–100 (post-trim) | none | "Full name must be between 2 and 100 characters." |
| `organizationName` | register, create org | yes | `strip()` | 2–80 (post-trim) | none | "Organization name must be between 2 and 80 characters." |
| `name` | PATCH current org | yes | `strip()` | 2–80 (post-trim) | none | "Organization name must be between 2 and 80 characters." |

**The 72-byte password cap is load-bearing:** BCrypt silently ignores input past 72 bytes, so without this cap two different long passwords could authenticate interchangeably. Enforce **both** the 72-character limit and the 72-UTF-8-byte limit — a multi-byte password can exceed 72 bytes at only 24 characters.

Upper bound rationale: the max lengths also cap work — reject oversized input before hashing so an attacker cannot force expensive BCrypt calls with megabyte passwords.

### 7.2 Normalization must run before validation

Use Java **records** and normalize in the compact constructor. Jackson invokes the canonical constructor during deserialization, so normalization completes *before* Bean Validation inspects the values — otherwise `"  Ab  "` would pass a `min=2` check that the trimmed value fails.

```java
public record RegisterRequest(
    @NotBlank(message = "Email is required.")
    @Size(max = 255, message = "Email must be at most 255 characters.")
    @Pattern(regexp = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$",
             message = "Enter a valid email address.")
    String email,

    @NotBlank(message = "Password is required.")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,72}$",
             message = "Password must be 8–72 characters and include at least one letter and one number.")
    String password,

    @NotBlank(message = "Full name is required.")
    @Size(min = 2, max = 100, message = "Full name must be between 2 and 100 characters.")
    String fullName,

    @NotBlank(message = "Organization name is required.")
    @Size(min = 2, max = 80, message = "Organization name must be between 2 and 80 characters.")
    String organizationName
) {
    public RegisterRequest {
        email = email == null ? null : email.strip().toLowerCase(java.util.Locale.ROOT);
        fullName = fullName == null ? null : fullName.strip();
        organizationName = organizationName == null ? null : organizationName.strip();
        // password is intentionally NOT trimmed
    }
}
```

The UTF-8 byte cap needs a custom check (a `@MaxUtf8Bytes(72)` validator, or an explicit service-layer guard that raises the same `VALIDATION_ERROR` with `field: "password"`).

### 7.3 Zod mirror

Chain `.trim()` / `.toLowerCase()` **before** `.min()`/`.max()`/`.regex()` — Zod applies string checks in declaration order, so ordering decides whether length is measured pre- or post-trim.

```ts
import { z } from "zod";

const EMAIL_REGEX = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;
const PASSWORD_REGEX = /^(?=.*[A-Za-z])(?=.*\d).{8,72}$/;

const utf8Bytes = (v: string) => new TextEncoder().encode(v).length;

const email = z.string().trim().toLowerCase()
  .min(1, "Email is required.")
  .max(255, "Email must be at most 255 characters.")
  .regex(EMAIL_REGEX, "Enter a valid email address.");

const organizationName = z.string().trim()
  .min(2, "Organization name must be between 2 and 80 characters.")
  .max(80, "Organization name must be between 2 and 80 characters.");

export const registerSchema = z.object({
  email,
  password: z.string()
    .min(1, "Password is required.")
    .regex(PASSWORD_REGEX, "Password must be 8–72 characters and include at least one letter and one number.")
    .refine((v) => utf8Bytes(v) <= 72, "Password must be at most 72 bytes."),
  fullName: z.string().trim()
    .min(2, "Full name must be between 2 and 100 characters.")
    .max(100, "Full name must be between 2 and 100 characters."),
  organizationName,
});

export const loginSchema = z.object({
  email,
  password: z.string().min(1, "Password is required.").max(72, "Password must be at most 72 characters."),
});

export const createOrganizationSchema = z.object({ organizationName });
export const renameOrganizationSchema = z.object({
  name: z.string().trim()
    .min(2, "Organization name must be between 2 and 80 characters.")
    .max(80, "Organization name must be between 2 and 80 characters."),
});
```

A confirm-password field, if the signup form has one, is **client-only** and must be stripped before the request — sending it would trip `fail-on-unknown-properties`.

---

## 8. Rate limiting

In-memory fixed-window counters (`ConcurrentHashMap` or Caffeine). Per-instance only — acceptable for M1; Redis-backed limiting is opt-in later.

| Endpoint | Limit | Key |
|---|---|---|
| `POST /api/auth/login` | 10 / 15 min | client IP + normalized email |
| `POST /api/auth/register` | 5 / 60 min | client IP |
| `POST /api/auth/refresh` | 60 / 15 min | client IP |
| `POST /api/organizations` | 20 / 60 min | authenticated user id |

On breach: `429` with the standard envelope, `code: "RATE_LIMITED"`, and a `Retry-After: <seconds>` header. Message: "Too many attempts. Please try again in N seconds." Counters are keyed on the **normalized** email so casing tricks do not reset the window. Client IP comes from the remote address; only honour `X-Forwarded-For` when a trusted proxy is configured (never in dev) — otherwise the header is a trivially spoofed bypass.

---

## 9. Logging & secret hygiene

* **Never logged, at any level:** passwords (raw or hashed), `accessToken`, refresh tokens, `Authorization` header values, `Cookie`/`Set-Cookie` header values.
* `logging.level.com.flowops: DEBUG` is on in `application.yml` — do **not** log request bodies for `/api/auth/*` under it.
* Log auth events as structured facts, never payloads: `event=login.success userId=<uuid> sessionId=<uuid> orgId=<uuid> ip=<ip>`; `event=login.failure email=<sha256-prefix-8> ip=<ip>` (hash the email, do not log it in the clear); `event=refresh.reuse_detected userId=<uuid>` at WARN; `event=org.switch userId=<uuid> from=<uuid> to=<uuid>`.
* Exception messages returned to clients must not embed identifiers from other tenants.
* Do not add `passwordHash` to any OpenAPI schema.

---

## 10. Reference schema (Flyway `V1__auth_and_orgs.sql`)

Included so both engineers agree on semantics. `TIMESTAMPTZ` everywhere; UUIDs generated in Java.

```
users
  id               uuid PK
  email            varchar(255) NOT NULL, UNIQUE (store lowercase; UNIQUE index on the lowercase value)
  password_hash    varchar(72)  NOT NULL   -- BCrypt, strength 12
  full_name        varchar(100) NOT NULL
  avatar_url       varchar(512) NULL
  created_at       timestamptz  NOT NULL
  updated_at       timestamptz  NOT NULL

organizations
  id               uuid PK
  name             varchar(80)  NOT NULL          -- NOT unique
  slug             varchar(100) NOT NULL UNIQUE
  created_at       timestamptz  NOT NULL
  updated_at       timestamptz  NOT NULL

organization_members
  id               uuid PK
  user_id          uuid NOT NULL FK -> users(id) ON DELETE CASCADE
  organization_id  uuid NOT NULL FK -> organizations(id) ON DELETE CASCADE
  role             varchar(16) NOT NULL CHECK (role IN ('OWNER','ADMIN','MEMBER','VIEWER'))
  joined_at        timestamptz NOT NULL
  UNIQUE (user_id, organization_id)
  INDEX (user_id), INDEX (organization_id)

auth_sessions                       -- one row per login; survives refresh rotation
  id               uuid PK          -- the `sid` claim
  user_id          uuid NOT NULL FK -> users(id) ON DELETE CASCADE
  organization_id  uuid NOT NULL FK -> organizations(id)   -- the switchable current org
  created_at       timestamptz NOT NULL
  last_used_at     timestamptz NOT NULL
  revoked_at       timestamptz NULL
  user_agent       varchar(255) NULL
  ip               varchar(45)  NULL
  INDEX (user_id)

refresh_tokens                      -- one row per rotation generation
  id               uuid PK
  session_id       uuid NOT NULL FK -> auth_sessions(id) ON DELETE CASCADE
  token_hash       char(64) NOT NULL UNIQUE     -- SHA-256 hex of the plaintext
  expires_at       timestamptz NOT NULL
  created_at       timestamptz NOT NULL
  used_at          timestamptz NULL
  revoked_at       timestamptz NULL
  INDEX (session_id), INDEX (token_hash)
```

BCrypt strength **12**. Note `password_hash` is 60 chars for BCrypt; the column is 72 for headroom.

---

## 11. Acceptance tests (both sides must pass)

1. Register → `201`, `AuthResponse`, `Set-Cookie: flowops_refresh` present and `HttpOnly`, `memberships.length == 1`, `currentRole == "OWNER"`.
2. Register with the same email in different case → `409 EMAIL_ALREADY_REGISTERED`; no second user or org is created.
3. Register with `"password": "short"` → `400 VALIDATION_ERROR`, `fieldErrors[0].field == "password"`.
4. Register with an extra `"role": "OWNER"` field → `400 MALFORMED_REQUEST`.
5. Register where org creation fails → **no** `users` row (transaction rollback).
6. Login with wrong password and login with an unknown email → byte-identical `401 INVALID_CREDENTIALS` bodies (modulo `timestamp`/`requestId`) and comparable latency.
7. `GET /api/auth/me` with no header → `401 AUTHENTICATION_REQUIRED`; with an expired token → `401 TOKEN_EXPIRED`; with a tampered signature → `401 TOKEN_INVALID`.
8. **No response body anywhere contains the substring `refreshToken` or `passwordHash`.**
9. Refresh with a valid cookie → `200`, new `accessToken`, **new** cookie value.
10. Refresh with the *previous* (rotated) cookie → `401 REFRESH_TOKEN_INVALID`, and all of that user's sessions are revoked.
11. Refresh as a cross-site form post (`Content-Type: application/x-www-form-urlencoded`) → `415 UNSUPPORTED_MEDIA_TYPE`.
12. Logout → `204` + cleared cookie; a second logout with no cookie → still `204`.
13. **Isolation:** user A (org 1) and user B (org 2). A's token switched to B's org id → `404 ORGANIZATION_NOT_FOUND`. A's `GET /api/organizations/current/members` never contains B.
14. Switch to a **nonexistent** org id → `404 ORGANIZATION_NOT_FOUND`, identical to the non-member response.
15. Switch to a legitimate second org → `200`, `currentOrganization.id` updated, `currentRole` reflects the *new* org's role, **no `Set-Cookie`**. Then reload → boot refresh returns the **switched** org.
16. `PATCH /api/organizations/current` as `VIEWER` → `403 FORBIDDEN_ROLE`; as `OWNER` → `200` with `slug` unchanged.
17. 11 rapid logins → the 11th is `429 RATE_LIMITED` with a `Retry-After` header.
18. Every error response validates against the §3 envelope, `fieldErrors` key present (array or `null`), and `X-Request-Id` is set.
19. Preflight `OPTIONS /api/auth/login` from `http://localhost:3000` → `200` with `Access-Control-Allow-Credentials: true` and a concrete (non-`*`) `Access-Control-Allow-Origin`.
20. Every repository method touching org-scoped data takes an org id parameter (static review).

---

## 12. New configuration

`backend/src/main/resources/application.yml`:

```yaml
spring:
  jackson:
    serialization:
      write-dates-as-timestamps: false
    deserialization:
      fail-on-unknown-properties: true
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/flowops}
    username: ${SPRING_DATASOURCE_USERNAME:flowops}
    password: ${SPRING_DATASOURCE_PASSWORD:flowops}
  jpa:
    hibernate.ddl-auto: validate      # Flyway owns the schema
    open-in-view: false
  flyway:
    enabled: true

server:
  error:
    whitelabel:
      enabled: false

flowops:
  auth:
    jwt-secret: ${JWT_SECRET}
    issuer: flowops
    audience: flowops-api
    access-token-ttl: 15m
    refresh-token-ttl: 7d
    bcrypt-strength: 12
    refresh-cookie:
      name: flowops_refresh
      path: /api/auth
      secure: ${REFRESH_COOKIE_SECURE:false}      # true in every non-local environment
      same-site: ${REFRESH_COOKIE_SAME_SITE:Lax}  # None in production
```

Fail fast at startup if `JWT_SECRET` is absent, shorter than 32 chars, or still equals the `.env.example` placeholder in a non-dev profile; and if `same-site: None` is paired with `secure: false`.

New Maven dependencies: `spring-boot-starter-security`, `spring-boot-starter-data-jpa`, `org.postgresql:postgresql` (runtime), `org.flywaydb:flyway-core`, `org.flywaydb:flyway-database-postgresql`, `io.jsonwebtoken:jjwt-api|jjwt-impl|jjwt-jackson:0.12.6`, `spring-security-test` (test).

Frontend: no new dependencies — `axios`, `zod`, `zustand`, and `@tanstack/react-query` are already in `package.json`.
