# Vantis SaaS Billing & Subscription Architecture

**Status:** Production-Ready Specification & Reference  
**Version:** 1.0 · October 2026  

---

## 1. Overview & Core Invariants

The Vantis billing and entitlements system provides robust, multi-tenant subscription management, entitlement enforcement, quota telemetry, and payment provider integrations (Stripe, Razorpay, Noop).

### Fundamental Invariants
1. **Private Beta Safety**: By default, `BILLING_ENABLED=false` and `PRIVATE_BETA=true`. Production billing cannot activate until both are explicitly enabled via deployment environment variables.
2. **Provider Fail-Fast**: When `BILLING_ENABLED=true`, missing or unknown providers (`provider=unknown`) fail fast at startup/configuration. Silent fallback to `NoopBillingProvider` is forbidden in production billing mode.
3. **Webhook-Authoritative State Transitions**: Local subscription status, plan changes, and renewal boundaries only transition upon receipt of verified cryptographic provider webhooks (or platform-admin override). Front-end actions create checkout/portal sessions but never preemptively mutate the subscription state.
4. **Zero JVM-Local Concurrency**: Distributed locks and webhook deduplication rely strictly on Redis and PostgreSQL atomic operations (`INSERT ... ON CONFLICT DO NOTHING`). No `String.intern()` or `synchronized` locks are used for cross-replica coordination.
5. **Data Preservation**: Beta expiry and subscription cancellation **never delete customer workflows, executions, integrations, or data**. When a subscription lapses or cancels, the organization transitions to the `FREE` tier, and access quotas are adjusted accordingly. Retention locking enforces historical execution limits non-destructively by setting `is_locked = true`.
6. **Multi-Tenant Isolation**: Every client-facing billing endpoint resolves the organization ID strictly from the authenticated JWT principal (`AuthenticatedUser.require()`). Client-supplied organization IDs are never accepted.

---

## 2. Plan Definitions & Catalog

Defined in `com.flowops.billing.model.PlanCatalog`:

| Metric / Feature | `FREE` | `PRIVATE_BETA` | `PRO` | `BUSINESS` |
|---|---|---|---|---|
| **Active Workflows** | 3 | 10 | 100 | 1,000 |
| **Executions / Month** | 1,000 | 50,000 | 50,000 | 1,000,000 |
| **AI Prompt Generations** | 5 | 500 | 500 | 10,000 |
| **Monthly AI Investigations** | 0 (2 lifetime trial) | 50 | 50 | 1,000 |
| **Team Seats** | 1 | 10 | 10 | 100 |
| **Workspaces** | 1 | 5 | 5 | 50 |
| **Execution Retention** | 3 days | 30 days | 30 days | 90 days |
| **Workflow Versions Kept** | 3 | 30 | 30 | 100 |
| **Anomaly Detection Mode** | Basic | Full | Full | Tunable |
| **Notification Channels** | Email, In-App, Webhook | Email, In-App, Webhook, Slack, Teams, Discord, Twilio, PagerDuty | Full Native Suite | Full Suite + Custom Webhooks |

---

## 3. Supported Providers & Abstraction

All billing providers implement `com.flowops.billing.provider.BillingProvider`:

```java
public interface BillingProvider {
    String getName();
    CustomerResult createCustomer(CreateCustomerRequest request);
    CheckoutSessionResult createCheckoutSession(CreateCheckoutSessionRequest request);
    PortalSessionResult createPortalSession(CreatePortalSessionRequest request);
    SubscriptionResult changePlan(ChangePlanRequest request);
    SubscriptionResult cancelSubscription(CancelSubscriptionRequest request);
    SubscriptionResult resumeSubscription(ResumeSubscriptionRequest request);
    List<InvoiceResult> listInvoices(String customerId);
    WebhookEventResult processWebhookEvent(String rawPayload, Map<String, String> headers);
    DiscountResult applyDiscount(ApplyDiscountRequest request);
}
```

### 3.1 NoopBillingProvider (`noop`)
- Used when `BILLING_ENABLED=false` or during non-production testing.
- Generates synthetic sessions without making external network calls.
- Safe for offline local development and staging environments.

### 3.2 StripeBillingProvider (`stripe`)
- Supported currencies: `USD`, `GBP`, `EUR`, `CAD`, `AUD`, `SGD`, `JPY`.
- Price IDs configured via: `flowops.billing.stripe.prices.<plan>.<currency>.<interval>`.
- Cryptographic webhook verification via `Webhook.constructEvent(...)` with tolerance and secret checking.
- Founding discount applied via Stripe Coupon API (`FOUNDING_30`, 30% off, duration: 12 months).

### 3.3 RazorpayBillingProvider (`razorpay`)
- Supported currency: `INR`.
- Plan IDs configured via: `flowops.billing.razorpay.plans.<plan>.<interval>`.
- Cryptographic HMAC-SHA256 signature verification over raw request body.
- Founding discount applied via Razorpay Subscription Offer/Discount API.

---

## 4. Webhook Ingress & Idempotency

### Ingress Flow:
1. `BillingWebhookController` (`POST /api/billing/webhook/{provider}`):
   - Reads the raw HTTP request body byte-for-byte to prevent payload mutation before HMAC signature verification.
2. `BillingEventClaimer`:
   - Claims provider events using PostgreSQL atomic INSERT:
   ```sql
   INSERT INTO billing_events (id, provider, provider_event_id, event_type, status, organization_id, created_at, updated_at)
   VALUES (?, ?, ?, ?, 'CLAIMED', ?, now(), now())
   ON CONFLICT (provider, provider_event_id) DO NOTHING
   RETURNING id;
   ```
   - If already claimed by another worker or previously processed, duplicate requests return immediately without re-executing state transitions.
3. Event Processing:
   - Handled events: `customer.subscription.created`, `customer.subscription.updated`, `customer.subscription.deleted`, `invoice.payment_succeeded`, `invoice.payment_failed`.
   - Transitions `Subscription` plan, status (`ACTIVE`, `PAST_DUE`, `CANCELED`), and renewal dates in an isolated database transaction.

---

## 5. Lifecycle Jobs & Distributed Scheduler

Managed by `BillingLifecycleScheduler` and `SubscriptionLifecycleService`:
1. **Beta Expiry**:
   - Downgrades expired beta workspaces to `FREE` tier once `beta_ends_at` passes.
   - Retains all user data and workflows.
2. **Execution History Locking**:
   - Queries executions older than the organization's plan retention window (`FREE`: 3 days, `PRO`: 30 days, `BUSINESS`: 90 days).
   - Marks executions `is_locked = true` without deleting logs or metadata.
3. **Usage Threshold Notices**:
   - Emits exactly-once notices when resource usage reaches 80% and 100% of quota.
   - Deduplicated via unique database constraint `(organization_id, notice_type, period_start)`.
4. **Distributed Redis NX Locking**:
   - Scheduler sweep uses `SET lock:billing:lifecycle <uuid> NX EX 300`.
   - Lock release uses atomic Lua script:
   ```lua
   if redis.call('get', KEYS[1]) == ARGV[1] then
       return redis.call('del', KEYS[1])
   else
       return 0
   end
   ```
   - Prevents race conditions and blind lock deletion across multiple backend replicas.

---

## 6. Billing API Endpoints

All endpoints under `/api/billing` require JWT authentication:

| Endpoint | Method | Required Role | Description |
|---|---|---|---|
| `/api/billing/usage` | `GET` | Authenticated | Live resource usage counts (workflows, executions, AI prompts, seats). |
| `/api/billing/subscription` | `GET` | Authenticated | Full subscription summary, active limits, and renewal dates. |
| `/api/billing/plans` | `GET` | Public | Available plan catalog, limits, and pricing metadata. |
| `/api/billing/entitlements` | `GET` | Authenticated | Structured entitlement quotas against active plan definition. |
| `/api/billing/status` | `GET` | Authenticated | Aggregated bootstrap status for frontend dashboard. |
| `/api/billing/checkout` | `POST` | `ADMIN` | Initiates provider checkout session (rate limited: 5 req/min). |
| `/api/billing/portal` | `POST` | `ADMIN` | Opens provider customer management portal. |
| `/api/billing/change-plan` | `POST` | `ADMIN` | Changes plan tier via billing provider. |
| `/api/billing/cancel` | `POST` | `ADMIN` | Schedules subscription cancellation at period end. |
| `/api/billing/resume` | `POST` | `ADMIN` | Resumes subscription pending cancellation. |
| `/api/billing/invoices` | `GET` | Authenticated | Lists recent provider invoices and payment receipts. |
| `/api/billing/founding-offer`| `POST` | `ADMIN` | Claims 30% discount on Pro tier for 12 months. |
| `/api/billing/admin/override`| `POST` | `PLATFORM_ADMIN` | Audited administrative override for plan and subscription status. |

---

## 7. Required Environment Variables

```bash
# -----------------------------------------------------------------------------
# Billing Core Configuration
# -----------------------------------------------------------------------------
BILLING_ENABLED=false                    # Must be true for production billing
PRIVATE_BETA=true                       # True during private beta period
BILLING_PROVIDER=noop                   # 'noop', 'stripe', or 'razorpay'
ENTITLEMENTS_ENFORCEMENT=log            # 'log' (audit-only) or 'enforce' (strict blocking)

# -----------------------------------------------------------------------------
# Stripe Configuration (Required when BILLING_PROVIDER=stripe)
# -----------------------------------------------------------------------------
STRIPE_SECRET_KEY=sk_live_...
STRIPE_WEBHOOK_SECRET=whsec_...

# Stripe Price IDs (Plan + Currency + Interval)
STRIPE_PRICE_PRO_USD_MONTHLY=price_...
STRIPE_PRICE_PRO_USD_YEARLY=price_...
STRIPE_PRICE_PRO_EUR_MONTHLY=price_...
STRIPE_PRICE_PRO_EUR_YEARLY=price_...
STRIPE_PRICE_PRO_GBP_MONTHLY=price_...
STRIPE_PRICE_PRO_GBP_YEARLY=price_...
STRIPE_PRICE_BUSINESS_USD_MONTHLY=price_...
STRIPE_PRICE_BUSINESS_USD_YEARLY=price_...

# -----------------------------------------------------------------------------
# Razorpay Configuration (Required when BILLING_PROVIDER=razorpay)
# -----------------------------------------------------------------------------
RAZORPAY_KEY_ID=rzp_live_...
RAZORPAY_KEY_SECRET=...
RAZORPAY_WEBHOOK_SECRET=...
RAZORPAY_PLAN_PRO_INR_MONTHLY=plan_...
RAZORPAY_PLAN_PRO_INR_YEARLY=plan_...
```

---

## 8. Production Deployment Verification Checklist

Prior to enabling production billing (`BILLING_ENABLED=true`):
1. [ ] Deploy database migrations `V21__billing_and_entitlements.sql`, `V22__rejected_executions_status.sql`, `V23__billing_events.sql`, `V24__execution_history_locking.sql`.
2. [ ] Verify Redis cluster is reachable for distributed rate limiting and scheduler locking.
3. [ ] Register webhook endpoints in provider dashboard pointing to `/api/billing/webhook/{provider}`.
4. [ ] Ensure webhook signing secret is populated in `STRIPE_WEBHOOK_SECRET` / `RAZORPAY_WEBHOOK_SECRET`.
5. [ ] Configure all required currency Price IDs in deployment secrets.
6. [ ] Set `BILLING_PROVIDER=stripe` (or `razorpay`) and `BILLING_ENABLED=true`.
7. [ ] Confirm platform-admin credentials are secure for administrative overrides.
