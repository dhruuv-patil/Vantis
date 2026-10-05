# Production Infrastructure Security Hardening Guide

**Platform:** Vantis  
**Classification:** Operational Security & Deployment Baseline  
**Scope:** Batch 2 — Production Infrastructure Security Hardening  

---

## 1. Overview & Architecture

Vantis is designed as a secure, multi-tenant workflow automation and reliability platform. This document defines the **production infrastructure security requirements, service topology, egress controls, database TLS enforcement, container hardening, secret injection standards, and reverse proxy guidelines**.

### Target Service Topology

```text
               PUBLIC INTERNET
                      │
                      ▼ HTTPS (443)
       ┌─────────────────────────────┐
       │ Ingress Proxy / Load Balancer│
       └──────────────┬──────────────┘
                      │
        ┌─────────────┴─────────────┐
        │                           │
        ▼ Port 3000                 ▼ Port 8080
┌───────────────┐           ┌────────────────────────────────┐
│   Frontend    │           │            Backend             │
│  (Next.js)    │           │       (Spring Boot API)        │
└───────────────┘           └───┬───────────┬────────────┬───┘
                                │           │            │
          Private Internal Net  │           │            │
         (backend-tier isolated)│           │            │
                                ▼           ▼            ▼
                        ┌───────────┐ ┌───────────┐ ┌──────────────┐
                        │PostgreSQL │ │   Redis   │ │  AI Service  │
                        │(TLS req.) │ │(Private)  │ │ (Private)    │
                        └───────────┘ └───────────┘ └──────────────┘
```

### Core Security Boundaries

1. **Public Ingress Boundary:** Only the Frontend (port 3000) and Backend API (port 8080) are reachable from ingress / reverse proxy networks.
2. **Internal Dependency Boundary:** PostgreSQL, Redis, and the AI Service reside strictly within the private backend tier. They must **never** be exposed directly to the public internet or publish public host ports.
3. **AI Service Privacy:** The AI Service is an internal microservice. It is called exclusively by the backend over internal networking using an authenticating `X-Service-Token`. It has no public endpoints.
4. **Outbound Boundary:** Workload egress allows only legitimate public integrations (external HTTPS on port 443 and SMTP on port 587/465), while explicitly denying cloud metadata services, private networks, and unauthorized ports.

---

## 2. Production PostgreSQL Connection Security & TLS

### Mandatory TLS Requirement

All production database connections to PostgreSQL **must enforce TLS encryption in transit**. Plaintext connections in the `prod` profile are prohibited and will fail application startup.

### Configuration

In the `prod` profile (`SPRING_PROFILES_ACTIVE=prod`), the default datasource URL is configured with `sslmode=require`:

```properties
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/flowops?sslmode=require
POSTGRES_SSL_MODE=require
```

This configuration is codified in `application-prod.yml`:

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://postgres:5432/flowops?sslmode=require}
    hikari:
      data-source-properties:
        sslmode: ${POSTGRES_SSL_MODE:require}
        ssl: ${POSTGRES_SSL:true}
```

### Strict Startup Validation (`DatabaseSecurityPropertiesValidator`)

To prevent accidental misconfiguration or downgrade attacks during deployment, `DatabaseSecurityPropertiesValidator` verifies connection properties when `SPRING_PROFILES_ACTIVE=prod`:

- **Rejected values:** `sslmode=disable`, `sslmode=allow`, `sslmode=prefer`, or `ssl=false`. Startup fails fast with an `IllegalStateException`.
- **Accepted values:** `sslmode=require`, `sslmode=verify-ca`, or `sslmode=verify-full`.
- **Local development separation:** In non-production profiles (`dev`, `test`, or default), the validator is inactive so local unencrypted developer databases or in-memory H2 remain fully functional.

### Managed Database CA Certificate Verification (`verify-full`)

For managed database providers (AWS RDS, GCP Cloud SQL, Azure Database for PostgreSQL, Supabase, Neon) requiring certificate authority validation:

1. Mount the provider's CA certificate bundle (e.g. `rds-ca-bundle.pem`) into the container filesystem (e.g. `/etc/ssl/certs/db-ca.pem`).
2. Configure `sslmode=verify-full` and `sslrootcert`:

```bash
SPRING_DATASOURCE_URL="jdbc:postgresql://db.prod.internal:5432/flowops?sslmode=verify-full&sslrootcert=/etc/ssl/certs/db-ca.pem"
POSTGRES_SSL_MODE="verify-full"
```

### Flyway Migration Inheritance

Flyway inherits the Spring `DataSource` bean configured by HikariCP. It automatically executes all schema migrations over the same TLS-encrypted connection. No separate Flyway configuration is required.

---

## 3. Production Network & Egress Hardening

While Vantis incorporates application-level SSRF safeguards (`SsrfGuard`), production infrastructure must implement **infrastructure-level defense-in-depth**.

### Egress Security Principles

1. **Deny Cloud Metadata Endpoints:**
   - Workload containers must never access `169.254.169.254/32` (AWS/GCP/Azure instance metadata service).
   - In AWS environments, configure IMDSv2 with `HttpPutResponseHopLimit = 1` on EC2 host instances to prevent containerized workloads from querying IMDS even if network routes exist.
   - Deny GCP metadata hostname `metadata.google.internal` / `169.254.169.254`.
   - Deny Azure IMDS endpoint `169.254.169.254`.

2. **Deny Internal / Private Network Ranges:**
   - Unless explicitly required for database or Redis peering, block outbound traffic to RFC 1918 ranges from workload containers:
     * `10.0.0.0/8`
     * `172.16.0.0/12`
     * `192.168.0.0/16`
     * `100.64.0.0/10` (Carrier-grade NAT)
     * `127.0.0.0/8` (Loopback)
     * `fd00::/8` (IPv6 Unique Local)

3. **Allow Legitimate Outbound Integrations:**
   Vantis legitimately requires outbound internet connectivity to communicate with external APIs and services:
   - External Webhook targets and user HTTP integration steps
   - Slack (`slack.com`, `hooks.slack.com`)
   - Discord (`discord.com`)
   - GitHub (`api.github.com`)
   - Jira (`api.atlassian.com`)
   - AI Model Providers (Google Gemini at `generativelanguage.googleapis.com`, or custom OpenAI-compatible gateways)
   - SMTP Relays (configured transactional email host, ports 587 or 465)
   - Do **NOT** block all outbound traffic, as this breaks legitimate automation workflows.

4. **Restrict Outbound Ports:**
   - Limit egress destinations to standard encrypted protocols:
     * Port **443** (HTTPS) for external APIs and AI providers
     * Port **587** / **465** (TLS/STARTTLS) for operator SMTP relay
   - Block all non-essential outbound ports (e.g. port 22 SSH, 23 Telnet, 25 unencrypted SMTP, 445 SMB, 389/636 LDAP, and external database ports 3306/5432/27017/6379 unless explicitly configured as external integration endpoints).

---

## 4. Service Network Isolation & Compose Topology

### Dual-Tier Architecture

To isolate internal services, Docker Compose implements two segmented bridge networks:

- `frontend-tier`: Ingress tier connecting frontend and backend.
- `backend-tier`: Private backend tier connecting backend, postgres, redis, and ai-service.

```text
Service      | frontend-tier | backend-tier | Exposed to Host (Prod)
─────────────┼───────────────┼──────────────┼────────────────────────
frontend     |      YES      |      NO      | Port 3000 (Ingress)
backend      |      YES      |      YES     | Port 8080 (Ingress)
postgres     |      NO       |      YES     | NONE (Private)
redis        |      NO       |      YES     | NONE (Private)
ai-service   |      NO       |      YES     | NONE (Private)
```

### Production Docker Compose Overrides

For production deployment via Docker Compose, use `docker-compose.prod.yml`:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

Key overrides in `docker-compose.prod.yml`:
- `postgres.ports: !reset []` strips the host port binding (`127.0.0.1:5432`), ensuring PostgreSQL is accessible only within `backend-tier`.
- `redis.ports: !reset []` strips the host port binding (`127.0.0.1:6379`), ensuring Redis is accessible only within `backend-tier`.
- `ai-service` publishes no host ports (`expose: - "8100"` only), accessible exclusively to the backend.
- `backend` profile is pinned to `SPRING_PROFILES_ACTIVE=prod` and datasource is pinned to TLS.

---

## 5. Container Hardening Standards

All service containers in Vantis adhere to container hardening standards:

1. **Non-Root Execution:**
   - `backend/Dockerfile`: Runs as unprivileged `USER flowops` (Temurin JRE).
   - `frontend/Dockerfile`: Runs as unprivileged `USER flowops` (Node Alpine).
   - `ai-service/Dockerfile`: Runs as unprivileged `USER flowops` (Python slim).

2. **Privilege Limitation:**
   - All services declare `security_opt: ["no-new-privileges:true"]`.
   - No privileged flags (`privileged: true` is forbidden).
   - Containers drop unnecessary Linux capabilities.

3. **Filesystem & Mount Security:**
   - No host root directories mounted.
   - Host Docker socket (`/var/run/docker.sock`) is **never** mounted.
   - Database persistence uses an isolated named volume (`postgres_data`).

4. **Secret Exclusion via `.dockerignore`:**
   - Dedicated `.dockerignore` files are maintained at repo root, `backend/`, `frontend/`, and `ai-service/`.
   - Explicitly blocks `.env`, `.env.*`, `*.pem`, `*.key`, `*.p12`, `*.crt`, `target/`, `node_modules/`, `__pycache__/`, test fixtures, and git metadata from entering Docker build contexts.

---

## 6. Secret Management & Injection

### Zero Committed Secrets

No production secrets, API keys, certificates, or credentials may ever be committed to version control. Production configuration expects secrets through environment variables or cloud secret-manager injection.

### Required Secret Categories

| Environment Variable | Description | Generation / Source |
|----------------------|-------------|---------------------|
| `JWT_SECRET` | HS256 secret for user authentication tokens (min 32 bytes) | `openssl rand -base64 48` |
| `ENCRYPTION_KEY` | AES-256-GCM symmetric key for credential encryption at rest | `openssl rand -base64 32` (exact 32 bytes) |
| `AI_SERVICE_TOKEN` | Shared authentication token for backend-to-AI microservice calls | `openssl rand -hex 32` |
| `POSTGRES_PASSWORD` | PostgreSQL user password | Strong random credential |
| `GEMINI_API_KEY` | Google Gemini API key (kept strictly in AI service) | Google AI Studio |
| `SMTP_PASSWORD` | Operator SMTP relay credential for password resets | Mail provider |

### Secret Manager Injection

In managed cloud environments (AWS, GCP, Kubernetes):
- **AWS:** Inject secrets via AWS Secrets Manager or SSM Parameter Store using ECS task definition / EKS external-secrets.
- **GCP:** Inject secrets via Google Cloud Secret Manager into Cloud Run or GKE workloads.
- **Kubernetes:** Use Kubernetes Secrets backed by External Secrets Operator (ESO) or HashiCorp Vault.

---

## 7. Production Health Checks & Actuator Security

### Actuator Hardening

Spring Boot Actuator endpoints can leak environment details, configuration properties, or datasource paths if misconfigured. In `application-prod.yml`, the management surface is strictly locked down:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      probes:
        enabled: true
      show-details: never
      show-components: never
  info:
    env:
      enabled: false
```

- Actuator never exposes sensitive endpoints: `env`, `configprops`, `beans`, `mappings`, `heapdump`, and `shutdown` are not exposed over the web.
- Actuator health responses return only `{"status": "UP"}` without revealing connection strings or hostnames.

### Service Probes

| Service | Probe Endpoint | Type | Details |
|---------|---------------|------|---------|
| Backend | `http://127.0.0.1:8080/health/ready` | Readiness | Verifies DB, Flyway migrations, and application startup. Returns HTTP 200. |
| Backend | `http://127.0.0.1:8080/health` | Liveness | Lightweight probe returning basic status without downstream load. |
| AI Service | `http://127.0.0.1:8100/health` | Internal Health | Validates service status and honest `ai_configured` boolean; no secrets exposed. |
| PostgreSQL | `pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}` | Docker Healthcheck | Container-internal execution. |
| Redis | `redis-cli ping` | Docker Healthcheck | Container-internal execution. |

---

## 8. Reverse Proxy & Trusted Proxy Configuration

### The Client IP & Rate Limiting Challenge

The Redis rate limiter (`RateLimiter.java`) resolves the caller's IP via `request.getRemoteAddr()`. It intentionally **does not** read arbitrary, untrusted `X-Forwarded-For` headers directly in Java code, because an untrusted client could easily send `X-Forwarded-For: 1.1.1.1` to spoof their identity and bypass rate limiting.

### Recommended Production Proxy Architecture

When deploying behind a reverse proxy (e.g. AWS Application Load Balancer, Cloudflare, NGINX, Envoy, Traefik):

```text
Client (IP: 203.0.113.50)
        │
        ▼
Reverse Proxy / ALB (IP: 10.0.1.10)
  - Strips incoming client-supplied X-Forwarded-For (or appends real IP)
  - Sets X-Forwarded-For: 203.0.113.50
  - Sets X-Forwarded-Proto: https
        │
        ▼
Spring Boot Backend (Tomcat RemoteIpValve)
  - Configured with trusted internal-proxies matching Reverse Proxy CIDR (10.0.1.0/24)
  - Rewrites request.getRemoteAddr() to 203.0.113.50
  - RateLimiter securely enforces IP-based thresholds against 203.0.113.50
```

### Spring Boot Proxy Properties

To allow Tomcat's `RemoteIpValve` to safely rewrite `getRemoteAddr()` **only** when requests arrive from trusted reverse proxies:

```yaml
server:
  forward-headers-strategy: native
  tomcat:
    remote-ip-header: x-forwarded-for
    protocol-header: x-forwarded-proto
    # Set to the specific CIDR of your reverse proxy or VPC private subnet:
    internal-proxies: 10\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}
```

> [!WARNING]
> Do NOT set `server.forward-headers-strategy: framework` globally without defining trusted proxies. Doing so blindly trusts any client-sent `X-Forwarded-For` header, allowing IP spoofing against the rate limiter.

---

## 9. Provider-Specific Deployment Checklist

Because Vantis is provider-agnostic, the following infrastructure controls cannot be verified within the repository and must be configured during deployment on your selected cloud hosting provider:

### Cloud Infrastructure Checklist

- [ ] **Virtual Private Cloud (VPC):** Provision isolated private subnets for PostgreSQL, Redis, and AI Service with no public route tables or internet gateways.
- [ ] **Security Groups / Firewall Rules:**
  - Ingress: Allow port 443 into Load Balancer. Allow Load Balancer -> Backend (port 8080) and Frontend (port 3000). Allow Backend -> PostgreSQL (5432), Redis (6379), AI Service (8100).
  - Deny: All direct ingress from internet to PostgreSQL, Redis, and AI Service.
- [ ] **Metadata Endpoint Blocking:**
  - AWS EC2 / ECS: Set `HttpPutResponseHopLimit = 1` on EC2 instances.
  - Egress Firewall: Block outbound routes to `169.254.169.254/32`.
- [ ] **Managed Database TLS:** Ensure database requires SSL/TLS (`rds.force_ssl=1` on AWS RDS). Distribute provider CA certificate bundle and set `sslmode=verify-full`.
- [ ] **Secret Manager Integration:** Store `JWT_SECRET`, `ENCRYPTION_KEY`, `AI_SERVICE_TOKEN`, `POSTGRES_PASSWORD`, and API keys in AWS Secrets Manager / GCP Secret Manager / Vault.
- [ ] **TLS Termination & Certificates:** Provision valid TLS certificates on the Ingress Load Balancer (ACM, Let's Encrypt) and enforce HTTP-to-HTTPS redirection (HTTP 301).
- [ ] **Trusted Proxy Header Sanitization:** Configure ingress proxy to overwrite untrusted `X-Forwarded-For` and `X-Forwarded-Proto` headers.
