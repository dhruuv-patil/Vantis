package com.flowops.billing.provider.razorpay;

import com.flowops.billing.provider.exception.BillingSignatureVerificationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Verifies Razorpay webhook signatures against raw unmodified request payloads.
 * Implements HMAC-SHA256 signature verification for X-Razorpay-Signature header.
 */
@Component
public class RazorpaySignatureVerifier {

    private static final String HMAC_SHA256 = "HmacSHA256";

    public void verify(String rawPayload, Map<String, String> headers, String webhookSecret) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new BillingSignatureVerificationException("Razorpay webhook secret is not configured");
        }

        String signatureHeader = findHeader(headers, "X-Razorpay-Signature");
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new BillingSignatureVerificationException("Missing X-Razorpay-Signature header");
        }

        String expectedHash = computeHmacSha256(rawPayload != null ? rawPayload : "", webhookSecret);
        if (!constantTimeEquals(signatureHeader.trim(), expectedHash)) {
            throw new BillingSignatureVerificationException("Razorpay webhook signature verification failed");
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
