package com.flowops.common.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Enterprise-grade sensitive data redactor ensuring zero secret leakage before
 * outbound AI payloads, logs, or external integrations.
 */
public final class SensitiveDataRedactor {

    private static final Pattern BEARER_PATTERN =
            Pattern.compile("(?i)(bearer\\s+)[a-zA-Z0-9_\\-\\.]+");
    private static final Pattern AUTH_HEADER_PATTERN =
            Pattern.compile("(?i)(authorization[\"':\\s=]+)(?:basic\\s+|bearer\\s+)?[^\\s,'\"}]+");
    private static final Pattern API_KEY_PATTERN =
            Pattern.compile("(?i)(api[_-]?key[\"':\\s=]+)[^\\s,'\"}]+");
    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("(?i)(password[\"':\\s=]+)[^\\s,'\"}]+");
    private static final Pattern SECRET_PATTERN =
            Pattern.compile("(?i)(secret[\"':\\s=]+)[^\\s,'\"}]+");
    private static final Pattern CREDIT_CARD_PATTERN =
            Pattern.compile("\\b(?:\\d{4}[-\\s]?){3}\\d{4}\\b");
    private static final Pattern PRIVATE_KEY_PATTERN =
            Pattern.compile("-----BEGIN [A-Z ]+PRIVATE KEY-----[^-]+-----END [A-Z ]+PRIVATE KEY-----", Pattern.DOTALL);
    private static final Pattern EMAIL_PII_PATTERN =
            Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");

    private static final Pattern SENSITIVE_KEY_PATTERN =
            Pattern.compile("(?i).*(password|secret|token|credential|authorization|apikey|api_key|private_key).*");

    private SensitiveDataRedactor() {
    }

    /**
     * Redacts known secret patterns from a string.
     */
    public static String redact(String text) {
        if (text == null) {
            return null;
        }

        String result = PRIVATE_KEY_PATTERN.matcher(text).replaceAll("[REDACTED_PRIVATE_KEY]");
        result = BEARER_PATTERN.matcher(result).replaceAll("$1[REDACTED]");
        result = AUTH_HEADER_PATTERN.matcher(result).replaceAll("$1[REDACTED]");
        result = API_KEY_PATTERN.matcher(result).replaceAll("$1[REDACTED]");
        result = PASSWORD_PATTERN.matcher(result).replaceAll("$1[REDACTED]");
        result = SECRET_PATTERN.matcher(result).replaceAll("$1[REDACTED]");
        result = CREDIT_CARD_PATTERN.matcher(result).replaceAll("[REDACTED_CC]");
        result = EMAIL_PII_PATTERN.matcher(result).replaceAll("[REDACTED_EMAIL]");

        return result;
    }

    /**
     * Recursively sanitizes a Map, redacting sensitive keys and values.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> redactMap(Map<String, ?> input) {
        if (input == null) {
            return null;
        }

        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : input.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (key != null && SENSITIVE_KEY_PATTERN.matcher(key).matches()) {
                copy.put(key, "[REDACTED]");
            } else if (value instanceof String s) {
                copy.put(key, redact(s));
            } else if (value instanceof Map<?, ?> m) {
                copy.put(key, redactMap((Map<String, ?>) m));
            } else if (value instanceof List<?> list) {
                copy.put(key, redactList(list));
            } else {
                copy.put(key, value);
            }
        }
        return copy;
    }

    /**
     * Recursively sanitizes a List, redacting strings, maps, and nested lists.
     */
    @SuppressWarnings("unchecked")
    public static List<Object> redactList(List<?> input) {
        if (input == null) {
            return null;
        }

        List<Object> copy = new ArrayList<>(input.size());
        for (Object item : input) {
            if (item instanceof String s) {
                copy.add(redact(s));
            } else if (item instanceof Map<?, ?> m) {
                copy.add(redactMap((Map<String, ?>) m));
            } else if (item instanceof List<?> nested) {
                copy.add(redactList(nested));
            } else {
                copy.add(item);
            }
        }
        return copy;
    }
}
