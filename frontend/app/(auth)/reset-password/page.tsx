"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import { Loader2 } from "lucide-react";

import { getErrorMessage, getFieldErrors, resetPassword } from "@/lib/api";
import { useAuthStore } from "@/lib/auth-store";
import { registerSchema, toFieldErrors } from "@/lib/validations";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

function ResetPasswordPageContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const status = useAuthStore((s) => s.status);

  const token = searchParams.get("token");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [formSuccess, setFormSuccess] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (status === "authenticated") {
      router.replace("/dashboard");
    }
    if (!token) {
      setFormError("Invalid or missing reset token.");
    }
  }, [status, router, token]);

  async function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();

    // Guard against duplicate submissions while a request is in flight.
    if (submitting) return;
    setFormError(null);
    setFormSuccess(null);

    if (!token) {
      setFormError("Invalid or missing reset token.");
      return;
    }

    // Validate password matches confirm
    if (password !== confirmPassword) {
      setErrors({ confirmPassword: "Passwords do not match." });
      return;
    }

    const parsed = registerSchema.shape.password.safeParse(password);

    if (!parsed.success) {
      setErrors(toFieldErrors(parsed.error));
      return;
    }

    setErrors({});
    setSubmitting(true);

    try {
      await resetPassword({ token, newPassword: parsed.data });
      setFormSuccess("Your password has been reset. Redirecting to sign in...");
      setTimeout(() => {
        router.replace("/login");
      }, 1500);
    } catch (err) {
      const fieldErrors = getFieldErrors(err);

      if (Object.keys(fieldErrors).length > 0) {
        setErrors(fieldErrors);
      }

      setFormError(getErrorMessage(err, "Could not reset your password. The link may have expired."));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="w-full">
      {/* Page title */}
      <div className="mb-9 text-center">
        <h1 className="text-[30px] font-semibold tracking-[-0.035em] text-foreground">
          Create a new password
        </h1>

        <p className="mt-2 text-sm text-muted-foreground">
          Your new password must be different from previous passwords.
        </p>
      </div>

      {/* Reset password card */}
      <div className="rounded-lg border border-border dark:border-white/[0.10] bg-card dark:bg-[#050505] p-7 shadow-sm dark:shadow-none">
        <div className="mb-7">
          <h2 className="text-[22px] font-semibold tracking-[-0.025em]">
            Set your new password
          </h2>

          <p className="mt-1.5 text-sm text-muted-foreground">
            Enter a strong password you haven&apos;t used before.
          </p>
        </div>

        {formError && (
          <div
            role="alert"
            className="mb-5 rounded-md border border-red-500/20 bg-red-500/[0.06] px-3.5 py-3 text-sm text-destructive"
          >
            {formError}
          </div>
        )}

        {formSuccess && (
          <div
            role="status"
            className="mb-5 rounded-md border border-green-500/20 bg-green-500/[0.06] px-3.5 py-3 text-sm text-green-400"
          >
            {formSuccess}
          </div>
        )}

        <form onSubmit={onSubmit} noValidate className="space-y-5">
          {/* Password */}
          <div className="space-y-2">
            <Label htmlFor="password" className="text-sm">
              New password
            </Label>

            <Input
              id="password"
              type="password"
              autoComplete="new-password"
              autoFocus
              value={password}
              onChange={(e) => {
                setPassword(e.target.value);
                if (errors.password) setErrors((prev) => { const n = { ...prev }; delete n.password; return n; });
              }}
              aria-invalid={!!errors.password}
              aria-describedby={
                errors.password ? "password-error" : undefined
              }
              placeholder="Create a new password"
              className="h-11 rounded-md border-input dark:border-white/[0.11] bg-background dark:bg-[#080808]"
              disabled={submitting}
            />

            {!errors.password ? (
              <p className="text-[11px] leading-5 text-muted-foreground/55">
                At least 8 characters, with a letter and a number.
              </p>
            ) : null}

            {errors.password && (
              <p id="password-error" className="text-xs text-destructive">
                {errors.password}
              </p>
            )}
          </div>

          {/* Confirm password */}
          <div className="space-y-2">
            <Label htmlFor="confirmPassword" className="text-sm">
              Confirm new password
            </Label>

            <Input
              id="confirmPassword"
              type="password"
              autoComplete="new-password"
              value={confirmPassword}
              onChange={(e) => {
                setConfirmPassword(e.target.value);
                if (errors.confirmPassword)
                  setErrors((prev) => { const n = { ...prev }; delete n.confirmPassword; return n; });
              }}
              aria-invalid={!!errors.confirmPassword}
              aria-describedby={
                errors.confirmPassword ? "confirmPassword-error" : undefined
              }
              placeholder="Confirm your new password"
              className="h-11 rounded-md border-input dark:border-white/[0.11] bg-background dark:bg-[#080808]"
              disabled={submitting}
            />

            {errors.confirmPassword && (
              <p id="confirmPassword-error" className="text-xs text-destructive">
                {errors.confirmPassword}
              </p>
            )}
          </div>

          {/* Submit */}
          <Button
            type="submit"
            disabled={submitting}
            className="h-11 w-full rounded-md bg-foreground text-background hover:bg-foreground/90 dark:bg-[#f3f3f1] dark:text-[#080808] dark:hover:bg-white text-sm font-medium"
          >
            {submitting ? (
              <>
                <Loader2 className="size-4 animate-spin" />
                Resetting password
              </>
            ) : (
              "Reset password"
            )}
          </Button>
        </form>

        {/* Divider */}
        <div className="my-6 flex items-center gap-3">
          <div className="h-px flex-1 bg-border dark:bg-white/[0.08]" />
          <span className="text-[11px] text-muted-foreground/60">
            OR
          </span>
          <div className="h-px flex-1 bg-border dark:bg-white/[0.08]" />
        </div>

        {/* Back to login */}
        <div className="text-center text-sm text-muted-foreground">
          Remember your password?{" "}
          <Link
            href="/login"
            className="font-medium text-foreground hover:underline"
          >
            Sign in
          </Link>
        </div>
      </div>
    </div>
  );
}

export default function ResetPasswordPage() {
  return (
    <Suspense fallback={null}>
      <ResetPasswordPageContent />
    </Suspense>
  );
}