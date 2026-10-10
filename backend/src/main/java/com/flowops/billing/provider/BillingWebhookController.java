package com.flowops.billing.provider;

import com.flowops.billing.provider.exception.BillingSignatureVerificationException;
import com.flowops.billing.provider.model.WebhookProcessResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public webhook ingress controller for billing providers (Stripe, Razorpay).
 * Ingests the unmodified raw request body directly to preserve cryptographic signatures.
 */
@RestController
@RequestMapping("/api/billing/webhook")
@Tag(name = "Billing Webhooks")
public class BillingWebhookController {

    private static final Logger log = LoggerFactory.getLogger(BillingWebhookController.class);

    private final BillingWebhookService webhookService;

    public BillingWebhookController(BillingWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/{provider}")
    @Operation(summary = "Ingest billing webhook from Stripe or Razorpay")
    public ResponseEntity<?> handleWebhook(
            @PathVariable("provider") String provider,
            HttpServletRequest request) {

        String rawBody;
        try {
            byte[] bodyBytes = request.getInputStream().readAllBytes();
            rawBody = new String(bodyBytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to read raw body from billing webhook request", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "MALFORMED_REQUEST", "message", "Cannot read request body"));
        }

        Map<String, String> headers = extractHeaders(request);

        try {
            WebhookProcessResult result = webhookService.processWebhook(provider, rawBody, headers);
            return ResponseEntity.ok(result);
        } catch (BillingSignatureVerificationException sve) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", sve.getErrorCode(), "message", sve.getMessage()));
        } catch (IllegalArgumentException iae) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "MALFORMED_REQUEST", "message", iae.getMessage()));
        }
    }

    private Map<String, String> extractHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                headers.put(headerName, request.getHeader(headerName));
            }
        }
        return headers;
    }
}
