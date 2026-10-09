package com.flowops.billing.provider.stripe;

import com.flowops.billing.provider.exception.BillingSignatureVerificationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Verifies Stripe webhook signatures against raw unmodified request payloads.
 * Implements Stripe's HMAC-SHA256 signature scheme (t=...,v1=...).
 */
@Component
public class StripeSignatureVerifier {

    private static final String HMAC_SHA256 = "HmacSHA256";

    public void verify(String rawPayload, Map<String, String> headers, String webhookSecret) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new BillingSignatureVerificationException("Stripe webhook secret is not configured");
        }

        String signatureHeader = findHeader(headers, "Stripe-Signature");
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new BillingSignatureVerificationException("Missing Stripe-Signature header");
        }

        String timestamp = null;
        boolean signatureMatched = false;

        String[] parts = signatureHeader.split(",");
        for (String part : parts) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2) {
                String key = kv[0].trim();
                String val = kv[1].trim();
                if ("t".equalsIgnoreCase(key)) {
                    timestamp = val;
                }
            }
        }

        if (timestamp == null || timestamp.isBlank()) {
            throw new BillingSignatureVerificationException("Stripe-Signature header missing timestamp element 't'");
        }

        String payloadToSign = timestamp + "." + (rawPayload != null ? rawPayload : "");
        String expectedHash = computeHmacSha256(payloadToSign, webhookSecret);

        for (String part : parts) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2) {
                String key = kv[0].trim();
                String val = kv[1].trim();
                if ("v1".equalsIgnoreCase(key)) {
                    if (constantTimeEquals(val, expectedHash)) {
                        signatureMatched = true;
                        break;
                    }
                }
            }
        }

        if (!signatureMatched) {
            throw new BillingSignatureVerificationException("Stripe webhook signature verification failed");
        }
    }

    public static String computeHmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(secretKeySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmacBytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256 signature", e);
        }
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private String findHeader(Map<String, String> headers, String target) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(target)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
