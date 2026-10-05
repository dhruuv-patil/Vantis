import {
  CURRENCY_METADATA,
  type BillingInterval,
  type SupportedCurrency,
} from "./pricing.config";

export interface FormatPriceOptions {
  /** Override formatting locale (defaults to currency's standard locale) */
  locale?: string;
  /** Force explicit minimum decimal fraction digits (default: 0) */
  minimumFractionDigits?: number;
  /** Force explicit maximum decimal fraction digits (default: 0 for integers / zero-decimal currencies, 2 for fractions) */
  maximumFractionDigits?: number;
}

/**
 * Formats an amount given in minor units (cents, paise, yen) to a localized currency string.
 * Uses Intl.NumberFormat with correct locale and handles zero-decimal currencies (like JPY).
 * 
 * Expected formats:
 * - INR: ₹1,999
 * - USD: $25
 * - GBP: £20
 * - EUR: 23 € or €23 depending on locale (e.g. "de-DE" -> "23 €")
 * - JPY: ¥3,500
 */
export function formatPriceFromMinor(
  amountMinor: number,
  currency: SupportedCurrency,
  options?: FormatPriceOptions,
): string {
  const meta = CURRENCY_METADATA[currency] ?? {
    code: currency,
    decimals: 2,
    defaultLocale: "en-US",
  };

  const divisor = Math.pow(10, meta.decimals);
  const amountMajor = meta.decimals === 0 ? amountMinor : amountMinor / divisor;

  return formatPriceFromMajor(amountMajor, currency, options);
}

/**
 * Formats a major amount (e.g. 25 for $25, 1999 for ₹1,999, 3500 for ¥3,500)
 * using Intl.NumberFormat. Does not hardcode symbol placement or separators.
 */
export function formatPriceFromMajor(
  amountMajor: number,
  currency: SupportedCurrency,
  options?: FormatPriceOptions,
): string {
  const meta = CURRENCY_METADATA[currency] ?? {
    code: currency,
    decimals: 2,
    defaultLocale: "en-US",
  };

  const targetLocale = options?.locale ?? meta.defaultLocale;
  const isInteger = Math.floor(amountMajor) === amountMajor;

  const minDigits = options?.minimumFractionDigits ?? 0;
  const maxDigits =
    options?.maximumFractionDigits ??
    (meta.decimals === 0 || isInteger ? 0 : meta.decimals);

  try {
    const formatter = new Intl.NumberFormat(targetLocale, {
      style: "currency",
      currency,
      minimumFractionDigits: minDigits,
      maximumFractionDigits: maxDigits,
    });
    return formatter.format(amountMajor).replace(/\uFFE5/g, "¥");
  } catch {
    // Graceful fallback if environment locale data is restricted
    return `${currency} ${amountMajor}`;
  }
}

/**
 * Formats a localized zero amount for the specified currency (e.g. ₹0, $0, £0, 0 €, ¥0).
 */
export function formatZeroPrice(
  currency: SupportedCurrency,
  locale?: string,
): string {
  return formatPriceFromMajor(0, currency, { locale, minimumFractionDigits: 0, maximumFractionDigits: 0 });
}

/**
 * Returns user-facing interval display string, e.g. "/ month" or "/ year".
 */
export function formatIntervalSuffix(
  interval: BillingInterval,
  short: boolean = false,
): string {
  if (short) {
    return interval === "YEARLY" ? "/yr" : "/mo";
  }
  return interval === "YEARLY" ? "/ year" : "/ month";
}
