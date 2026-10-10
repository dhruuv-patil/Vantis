package com.flowops.billing.provider.razorpay;

import com.flowops.billing.provider.exception.BillingConfigurationException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for Razorpay billing provider.
 * Price IDs are strictly configuration-driven and keyed by plan + currency + interval (INR).
 */
@Component
@ConfigurationProperties(prefix = "flowops.billing.razorpay")
public class RazorpayProperties {

    public static final Set<String> SUPPORTED_CURRENCIES = Set.of("INR");
    public static final List<String> REQUIRED_PLANS = List.of("PRO", "BUSINESS");
    public static final List<String> REQUIRED_INTERVALS = List.of("MONTHLY", "YEARLY");

    private String keyId = "";
    private String keySecret = "";
    private String webhookSecret = "";
    private Map<String, String> prices = new HashMap<>();

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId != null ? keyId.trim() : "";
    }

    public String getKeySecret() {
        return keySecret;
    }

    public void setKeySecret(String keySecret) {
        this.keySecret = keySecret != null ? keySecret.trim() : "";
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret != null ? webhookSecret.trim() : "";
    }

    public Map<String, String> getPrices() {
        return prices;
    }

    public void setPrices(Map<String, String> prices) {
        this.prices = prices != null ? new HashMap<>(prices) : new HashMap<>();
    }

    /**
     * Resolves the configured price ID for plan + currency + interval.
     */
    public Optional<String> getPriceId(String plan, String currency, String interval) {
        if (plan == null || currency == null || interval == null) {
            return Optional.empty();
        }

        String p = plan.trim().toLowerCase(Locale.ROOT);
        String c = currency.trim().toLowerCase(Locale.ROOT);
        String i = interval.trim().toLowerCase(Locale.ROOT);

        String[] candidates = new String[] {
                p + "." + c + "." + i,
                p + "." + i + "." + c,
                p + "_" + c + "_" + i,
                p + "_" + i + "_" + c
        };

        for (String key : candidates) {
            String val = prices.get(key);
            if (val != null && !val.isBlank()) {
                return Optional.of(val.trim());
            }
        }

        return Optional.empty();
    }

    /**
     * Validates that all required Razorpay credentials and price mappings exist.
     * Fails fast if any required price ID is missing.
     */
    public void validateConfiguration() {
        List<String> errors = new ArrayList<>();

        if (keyId.isBlank()) {
            errors.add("flowops.billing.razorpay.key-id is required but not configured");
        }
        if (keySecret.isBlank()) {
            errors.add("flowops.billing.razorpay.key-secret is required but not configured");
        }
        if (webhookSecret.isBlank()) {
            errors.add("flowops.billing.razorpay.webhook-secret is required but not configured");
        }

        for (String plan : REQUIRED_PLANS) {
            for (String currency : SUPPORTED_CURRENCIES) {
                for (String interval : REQUIRED_INTERVALS) {
                    if (getPriceId(plan, currency, interval).isEmpty()) {
                        errors.add(String.format("Missing required Razorpay price ID for plan=%s, currency=%s, interval=%s (key: %s.%s.%s)",
                                plan, currency, interval,
                                plan.toLowerCase(Locale.ROOT),
                                currency.toLowerCase(Locale.ROOT),
                                interval.toLowerCase(Locale.ROOT)));
                    }
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new BillingConfigurationException("Razorpay billing configuration validation failed:\n" + String.join("\n", errors));
        }
    }
}
