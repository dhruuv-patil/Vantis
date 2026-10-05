/**
 * Country Detection Service.
 * 
 * Resolves the customer's billing country in strict priority order:
 * 1. Existing subscription's stored billing country (always wins)
 * 2. Authenticated user's saved billing country
 * 3. Workspace billing country
 * 4. Payment-provider customer country, if available
 * 5. Server-side IP geolocation (CDN/edge headers: CF-IPCountry, x-vercel-ip-country, etc.)
 * 6. Browser locale region (fallback only; never sole signal when better data exists)
 * 7. DEFAULT_COUNTRY environment variable, then fallback to "US" (USD)
 * 
 * Guaranteed: Detection must NEVER throw or break the pricing page.
 * Any parsing error or invalid format falls through cleanly to the next source.
 */

export interface CountryDetectionContext {
  /** 1. Existing subscription's stored billing country */
  subscriptionBillingCountry?: string | null;
  /** 2. Authenticated user's saved billing country */
  userBillingCountry?: string | null;
  /** 3. Workspace billing country */
  workspaceBillingCountry?: string | null;
  /** 4. Payment-provider customer country */
  providerCustomerCountry?: string | null;
  /** 5 & 6. HTTP headers (Next.js Request Headers or Node IncomingHttpHeaders) */
  headers?: Headers | Record<string, string | string[] | undefined> | null;
  /** Optional direct IP (for future geoip DB lookup) */
  clientIp?: string | null;
  /** Optional explicit browser locale string (e.g. "en-GB") */
  browserLocale?: string | null;
}

export interface CountryDetectionResult {
  country: string;
  source:
    | "subscription"
    | "user"
    | "workspace"
    | "provider"
    | "ip_geolocation"
    | "browser_locale"
    | "default_country_env"
    | "fallback_default";
}

/**
 * Validates and normalizes a 2-character ISO country code.
 */
function sanitizeCountryCode(val?: unknown): string | null {
  if (typeof val !== "string") return null;
  const trimmed = val.trim().toUpperCase();
  // Valid ISO 3166-1 alpha-2 code: 2 uppercase letters, not "XX" or "T1" (Tor)
  if (/^[A-Z]{2}$/.test(trimmed) && trimmed !== "XX" && trimmed !== "T1") {
    return trimmed;
  }
  return null;
}

/**
 * Reads a header value case-insensitively from either Web standard Headers or a plain Record.
 */
function getHeaderValue(
  headers: Headers | Record<string, string | string[] | undefined> | null | undefined,
  headerName: string,
): string | null {
  if (!headers) return null;

  try {
    if (typeof (headers as Headers).get === "function") {
      return (headers as Headers).get(headerName);
    }

    const rec = headers as Record<string, string | string[] | undefined>;
    const lowerName = headerName.toLowerCase();
    for (const key of Object.keys(rec)) {
      if (key.toLowerCase() === lowerName) {
        const val = rec[key];
        if (Array.isArray(val)) return val[0] ?? null;
        return val ?? null;
      }
    }
  } catch {
    // Fail silently on header access errors
  }

  return null;
}

/**
 * Extracts candidate country code from standard edge/CDN geolocation headers.
 */
function extractEdgeCountry(
  headers: Headers | Record<string, string | string[] | undefined> | null | undefined,
): string | null {
  if (!headers) return null;

  const candidateHeaders = [
    "cf-ipcountry",                // Cloudflare
    "x-vercel-ip-country",         // Vercel Edge
    "cloudfront-viewer-country",   // AWS CloudFront
    "x-country-code",              // Generic reverse proxies
    "fastly-client-country",       // Fastly
    "x-geo-country",               // Akamai/Generic
    "x-appengine-country",         // Google App Engine / Cloud Run
  ];

  for (const name of candidateHeaders) {
    const val = getHeaderValue(headers, name);
    const sanitized = sanitizeCountryCode(val);
    if (sanitized) {
      return sanitized;
    }
  }

  return null;
}

/**
 * Extracts country code from Accept-Language header or browser locale string.
 * Example: "en-GB,en-US;q=0.9" -> "GB"
 */
function extractLocaleCountry(
  localeOrAcceptLanguage?: string | null,
): string | null {
  if (!localeOrAcceptLanguage || typeof localeOrAcceptLanguage !== "string") {
    return null;
  }

  try {
    // Split on commas to test preferences in order
    const parts = localeOrAcceptLanguage.split(",");
    for (const part of parts) {
      const tag = part.split(";")[0]?.trim();
      if (!tag) continue;

      // Match language-REGION, e.g. "en-GB", "en_IN", "de-DE", "ja-JP"
      const match = tag.match(/^[a-z]{2,3}[-_]([a-z]{2})$/i);
      if (match && match[1]) {
        const candidate = sanitizeCountryCode(match[1]);
        if (candidate) {
          return candidate;
        }
      }
    }
  } catch {
    // Fail silently
  }

  return null;
}

/**
 * Resolves the billing country following strict priority order.
 * This function NEVER throws.
 */
export function resolveBillingCountry(
  context?: CountryDetectionContext,
): CountryDetectionResult {
  try {
    // Priority 1: Existing subscription's stored billing country (always wins)
    const subCountry = sanitizeCountryCode(context?.subscriptionBillingCountry);
    if (subCountry) {
      return { country: subCountry, source: "subscription" };
    }

    // Priority 2: Authenticated user's saved billing country
    const userCountry = sanitizeCountryCode(context?.userBillingCountry);
    if (userCountry) {
      return { country: userCountry, source: "user" };
    }

    // Priority 3: Workspace billing country
    const wsCountry = sanitizeCountryCode(context?.workspaceBillingCountry);
    if (wsCountry) {
      return { country: wsCountry, source: "workspace" };
    }

    // Priority 4: Payment-provider customer country
    const providerCountry = sanitizeCountryCode(context?.providerCustomerCountry);
    if (providerCountry) {
      return { country: providerCountry, source: "provider" };
    }

    // Priority 5: Server-side IP geolocation (CDN/edge headers)
    const edgeCountry = extractEdgeCountry(context?.headers);
    if (edgeCountry) {
      return { country: edgeCountry, source: "ip_geolocation" };
    }

    // Priority 6: Browser locale region (fallback only)
    const localeCountryFromHeader = extractLocaleCountry(
      getHeaderValue(context?.headers, "accept-language"),
    );
    if (localeCountryFromHeader) {
      return { country: localeCountryFromHeader, source: "browser_locale" };
    }

    const localeCountryFromArg = extractLocaleCountry(context?.browserLocale);
    if (localeCountryFromArg) {
      return { country: localeCountryFromArg, source: "browser_locale" };
    }

    // Priority 7: DEFAULT_COUNTRY env, then US
    const envCountry = sanitizeCountryCode(process.env.DEFAULT_COUNTRY);
    if (envCountry) {
      return { country: envCountry, source: "default_country_env" };
    }

    return { country: "US", source: "fallback_default" };
  } catch {
    // Fail-safe guarantee: Never throw or break
    return { country: "US", source: "fallback_default" };
  }
}
