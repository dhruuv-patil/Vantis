package com.flowops.billing;

import com.flowops.billing.model.PlanCatalog;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Backend plan configuration delegating directly to PlanCatalog as single source of truth.
 */
@Component
public class PlanConfig {

    private final Map<String, PlanLimits> limits;
    private final Boolean billingEnabledOverride;
    private final Boolean privateBetaOverride;

    public PlanConfig() {
        this(null, null);
    }

    public PlanConfig(Boolean billingEnabledOverride, Boolean privateBetaOverride) {
        Map<String, PlanLimits> map = new LinkedHashMap<>();
        for (var entry : PlanCatalog.getAll().entrySet()) {
            map.put(entry.getKey(), PlanLimits.from(entry.getValue().limits()));
        }
        this.limits = Collections.unmodifiableMap(map);
        this.billingEnabledOverride = billingEnabledOverride;
        this.privateBetaOverride = privateBetaOverride;
    }

    public Map<String, PlanLimits> getAllLimits() {
        return limits;
    }

    public PlanLimits getLimits(String planId) {
        if (planId == null) {
            return limits.get(defaultPlan());
        }
        return limits.getOrDefault(planId.toUpperCase(), limits.get(defaultPlan()));
    }

    public boolean isPrivateBeta() {
        if (privateBetaOverride != null) {
            return privateBetaOverride;
        }
        String env = System.getenv("PRIVATE_BETA");
        return env == null || env.isBlank() || !"false".equalsIgnoreCase(env);
    }

    public boolean isBillingEnabled() {
        if (billingEnabledOverride != null) {
            return billingEnabledOverride;
        }
        String env = System.getenv("BILLING_ENABLED");
        return "true".equalsIgnoreCase(env) || "1".equals(env);
    }

    public String defaultPlan() {
        return isPrivateBeta() ? "PRIVATE_BETA" : "FREE";
    }

    public String defaultCountry() {
        String env = System.getenv("DEFAULT_COUNTRY");
        return (env != null && !env.isBlank()) ? env.trim().toUpperCase() : "US";
    }

    public String defaultCurrency() {
        String env = System.getenv("DEFAULT_CURRENCY");
        return (env != null && !env.isBlank()) ? env.trim().toUpperCase() : "USD";
    }
}
