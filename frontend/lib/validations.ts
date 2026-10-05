import { z } from "zod";

/**
 * Client mirror of the API contract's validation rules (§7).
 *
 * The two regexes below are used verbatim on both sides — deliberately *not*
 * Zod's `.email()`, whose edge-case behaviour differs from the backend's
 * `@Pattern`, which would let one side accept what the other rejects.
 *
 * Ordering matters: `.trim()` / `.toLowerCase()` are chained *before*
 * `.min()` / `.max()` / `.regex()`, because Zod applies string checks in
 * declaration order and the backend measures length on the normalized value.
 */

const EMAIL_REGEX = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;
const PASSWORD_REGEX = /^(?=.*[A-Za-z])(?=.*\d).{8,72}$/;

/**
 * BCrypt silently ignores input past 72 bytes, so two different long passwords
 * could otherwise authenticate interchangeably. Both the 72-character and the
 * 72-UTF-8-byte caps are enforced: a multi-byte password can exceed 72 bytes at
 * only 24 characters.
 */
const utf8Bytes = (value: string) => new TextEncoder().encode(value).length;

const email = z
  .string()
  .trim()
  .toLowerCase()
  .min(1, "Email is required.")
  .max(255, "Email must be at most 255 characters.")
  .regex(EMAIL_REGEX, "Enter a valid email address.");

const organizationName = z
  .string()
  .trim()
  .min(2, "Organization name must be between 2 and 80 characters.")
  .max(80, "Organization name must be between 2 and 80 characters.");

export const registerSchema = z.object({
  email,
  password: z
    .string()
    .min(1, "Password is required.")
    .regex(
      PASSWORD_REGEX,
      "Password must be 8–72 characters and include at least one letter and one number.",
    )
    .refine((v) => utf8Bytes(v) <= 72, "Password must be at most 72 bytes."),
  fullName: z
    .string()
    .trim()
    .min(2, "Full name must be between 2 and 100 characters.")
    .max(100, "Full name must be between 2 and 100 characters."),
  organizationName: organizationName.optional(),
});

export const signupSchema = z
  .object({
    fullName: z
      .string()
      .trim()
      .min(2, "Name must be between 2 and 100 characters.")
      .max(100, "Name must be between 2 and 100 characters."),
    email,
    password: z
      .string()
      .min(1, "Password is required.")
      .regex(
        PASSWORD_REGEX,
        "Password must be 8–72 characters and include at least one letter and one number.",
      )
      .refine((v) => utf8Bytes(v) <= 72, "Password must be at most 72 bytes."),
    confirmPassword: z.string().min(1, "Confirm your password."),
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: "Passwords do not match.",
    path: ["confirmPassword"],
  });

/**
 * Login checks presence and max length only — never the complexity rule. A
 * complexity check here would reject a legitimate legacy credential and leak
 * that stored passwords differ in form.
 */
export const loginSchema = z.object({
  email,
  password: z
    .string()
    .min(1, "Password is required.")
    .max(72, "Password must be at most 72 characters."),
});

/**
 * Flatten a Zod failure into `{ fieldName: firstMessage }`, matching the shape
 * the API's `fieldErrors` array is mapped into so a form renders both sources
 * of truth identically.
 */
export function toFieldErrors(error: z.ZodError): Record<string, string> {
  const out: Record<string, string> = {};
  for (const issue of error.issues) {
    const field = issue.path[0];
    if (typeof field !== "string" || field in out) continue;
    out[field] = issue.message;
  }
  return out;
}
