import {
  COUNTRY_TO_CURRENCY_MAP,
  CURRENCY_METADATA,
  EUROZONE_COUNTRIES,
  SUPPORTED_CURRENCIES,
  type SupportedCurrency,
} from "./pricing.config";

export interface BillingCurrencyResolution {
  country: string;
  currency: SupportedCurrency;
  symbol: string;
}

/**
 * Derives the currency symbol using standard Intl formatting rather than a hardcoded lookup map.
 */
export function deriveCurrencySymbol(
  currency: SupportedCurrency,
  locale?: string,
): string {
  const targetLocale =
    locale ?? CURRENCY_METADATA[currency]?.defaultLocale ?? "en-US";

  try {
    const formatter = new Intl.NumberFormat(targetLocale, {
      style: "currency",
      currency,
      currencyDisplay: "narrowSymbol",
    });
    const parts = formatter.formatToParts(0);
    const sym = parts.find((p) => p.type === "currency")?.value;
    if (sym && sym.trim().length > 0) {
      // Normalize fullwidth Japanese Yen symbol \uFFE5 to standard \u00A5
      return sym.replace(/\uFFE5/g, "¥");
    }
  } catch {
    // Fallback if Intl fails
  }

  // Safe standard fallbacks if Intl runtime lacks narrowSymbol
  const fallbackMap: Record<SupportedCurrency, string> = {
    INR: "₹",
    USD: "$",
    GBP: "£",
    EUR: "€",
    CAD: "$",
    AUD: "$",
    SGD: "$",
    JPY: "¥",
  };
  return fallbackMap[currency] ?? "$";
}

/**
 * Type guard for SupportedCurrency.
 */
export function isSupportedCurrency(
  currency: unknown,
): currency is SupportedCurrency {
  return (
    typeof currency === "string" &&
    (SUPPORTED_CURRENCIES as readonly string[]).includes(currency)
  );
}

/**
 * Resolves the billing currency and derived symbol for a given ISO 3166-1 alpha-2 country.
 * 
 * Rules:
 * - IN → INR, US → USD, GB → GBP, CA → CAD, AU → AUD, SG → SGD, JP → JPY
 * - Eurozone countries (DE, FR, IT, ES, NL, IE, etc.) → EUR
 * - Non-euro EU countries (PL, SE, DK, etc.) fall back to USD unless explicitly configured
 * - Unsupported or missing country → USD (with normalized country or "US" fallback)
 * - Derive symbols with Intl, not a hardcoded map
 */
export function getBillingCurrency(
  country?: string | null,
): BillingCurrencyResolution {
  if (!country || typeof country !== "string" || country.trim().length === 0) {
    const defaultCurrency: SupportedCurrency = "USD";
    return {
      country: "US",
      currency: defaultCurrency,
      symbol: deriveCurrencySymbol(defaultCurrency),
    };
  }

  const normalizedCountry = country.trim().toUpperCase();

  // Check 1: Direct single-country currency mapping
  if (normalizedCountry in COUNTRY_TO_CURRENCY_MAP) {
    const currency = COUNTRY_TO_CURRENCY_MAP[normalizedCountry];
    return {
      country: normalizedCountry,
      currency,
      symbol: deriveCurrencySymbol(
        currency,
        CURRENCY_METADATA[currency]?.defaultLocale,
      ),
    };
  }

  // Check 2: Eurozone member states
  if (EUROZONE_COUNTRIES.has(normalizedCountry)) {
    const currency: SupportedCurrency = "EUR";
    return {
      country: normalizedCountry,
      currency,
      symbol: deriveCurrencySymbol(currency, "de-DE"),
    };
  }

  // Check 3: Unsupported country or non-Euro EU country -> Fallback to USD
  const currency: SupportedCurrency = "USD";
  return {
    country: normalizedCountry,
    currency,
    symbol: deriveCurrencySymbol(currency, "en-US"),
  };
}
