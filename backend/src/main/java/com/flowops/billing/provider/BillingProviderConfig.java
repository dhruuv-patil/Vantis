package com.flowops.billing.provider;

import com.flowops.billing.provider.exception.BillingConfigurationException;
import com.flowops.billing.provider.razorpay.RazorpayBillingProvider;
import com.flowops.billing.provider.razorpay.RazorpayProperties;
import com.flowops.billing.provider.stripe.StripeBillingProvider;
import com.flowops.billing.provider.stripe.StripeProperties;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Spring configuration for selecting the active BillingProvider.
 *
 * Rules:
 * - When BILLING_ENABLED=false, NoopBillingProvider is strictly selected and external provider checks are skipped.
 * - When BILLING_ENABLED=true:
 *     - provider=stripe requires valid Stripe credentials and all configured price IDs; fails startup otherwise.
 *     - provider=razorpay requires valid Razorpay credentials and all configured price IDs; fails startup otherwise.
 *     - provider=noop uses NoopBillingProvider (allowed for non-production/test environments).
 *     - Any other/unknown provider name FAILS FAST at startup rather than silently falling back.
 */
@Configuration
public class BillingProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(BillingProviderConfig.class);

    @Bean
    @Primary
    public BillingProvider billingProvider(
            @Value("${flowops.billing.enabled:false}") boolean billingEnabled,
            @Value("${flowops.billing.provider:noop}") String providerName,
            NoopBillingProvider noopBillingProvider,
            org.springframework.beans.factory.ObjectProvider<StripeBillingProvider> stripeBillingProvider,
            org.springframework.beans.factory.ObjectProvider<RazorpayBillingProvider> razorpayBillingProvider,
            StripeProperties stripeProperties,
            RazorpayProperties razorpayProperties) {

        return selectProvider(billingEnabled, providerName, noopBillingProvider,
                stripeBillingProvider.getIfAvailable(), razorpayBillingProvider.getIfAvailable(),
                stripeProperties, razorpayProperties);
    }

    public BillingProvider selectProvider(
            boolean billingEnabled,
            String providerName,
            NoopBillingProvider noopBillingProvider,
            StripeBillingProvider stripeBillingProvider,
            RazorpayBillingProvider razorpayBillingProvider,
            StripeProperties stripeProperties,
            RazorpayProperties razorpayProperties) {

        if (!billingEnabled) {
            log.info("Billing is disabled (flowops.billing.enabled=false). Active provider is NoopBillingProvider.");
            return noopBillingProvider;
        }

        String normalizedProvider = providerName != null ? providerName.trim().toLowerCase(Locale.ROOT) : "";

        if ("noop".equals(normalizedProvider)) {
            log.info("Billing provider explicitly configured as 'noop'. Active provider is NoopBillingProvider.");
            return noopBillingProvider;
        }

        if ("stripe".equals(normalizedProvider)) {
            log.info("Validating Stripe billing provider configuration...");
            stripeProperties.validateConfiguration();
            if (stripeBillingProvider == null) {
                throw new BillingConfigurationException("StripeBillingProvider bean is not available in application context");
            }
            log.info("Stripe configuration verified. Active billing provider is StripeBillingProvider.");
            return stripeBillingProvider;
        }

        if ("razorpay".equals(normalizedProvider)) {
            log.info("Validating Razorpay billing provider configuration...");
            razorpayProperties.validateConfiguration();
            if (razorpayBillingProvider == null) {
                throw new BillingConfigurationException("RazorpayBillingProvider bean is not available in application context");
            }
            log.info("Razorpay configuration verified. Active billing provider is RazorpayBillingProvider.");
            return razorpayBillingProvider;
        }

        throw new BillingConfigurationException(String.format(
                "Unknown billing provider '%s' configured while billing is enabled. Allowed providers are: 'stripe', 'razorpay', 'noop'.",
                providerName));
    }
}
