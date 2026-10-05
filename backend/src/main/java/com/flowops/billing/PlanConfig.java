package com.flowops.billing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Single backend source of truth for plan limits, pricing, and private beta status.
 */
@Component
public class PlanConfig {

    private final Map<String, PlanLimits> limits;

    public PlanConfig() {
        Map<String, PlanLimits> map = new LinkedHashMap<>();
        map.put("FREE", new PlanLimits(5, 500, 10, 1));
        map.put("PRIVATE_BETA", new PlanLimits(50, 10_000, 100, 5));
        map.put("PRO", new PlanLimits(100, 50_000, 500, 10));
        map.put("BUSINESS", new PlanLimits(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
        this.limits = Collections.unmodifiableMap(map);
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
        String env = System.getenv("PRIVATE_BETA");
        return env == null || env.isBlank() || !"false".equalsIgnoreCase(env);
    }

    public boolean isBillingEnabled() {
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
