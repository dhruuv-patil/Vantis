"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import { Loader2 } from "lucide-react";

import { getErrorMessage, getFieldErrors, forgotPassword } from "@/lib/api";
import { useAuthStore } from "@/lib/auth-store";
import { loginSchema, toFieldErrors } from "@/lib/validations";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

function ForgotPasswordPageContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const status = useAuthStore((s) => s.status);

  const [email, setEmail] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [formSuccess, setFormSuccess] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (status === "authenticated") {
      router.replace("/dashboard");
    }
  }, [status, router]);

  async function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();

    // Guard against duplicate submissions while a request is in flight.
    if (submitting) return;
    setFormError(null);
    setFormSuccess(null);

    const parsed = loginSchema.shape.email.safeParse(email);

    if (!parsed.success) {
      setErrors(toFieldErrors(parsed.error));
      return;
    }

    setErrors({});
    setSubmitting(true);

    try {
      await forgotPassword({ email: parsed.data });
      setFormSuccess("If an account exists for that email, a reset link has been sent.");
    } catch (err) {
      const fieldErrors = getFieldErrors(err);

      if (Object.keys(fieldErrors).length > 0) {
        setErrors(fieldErrors);
      }

      setFormError(getErrorMessage(err, "Could not process your request."));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="w-full">
      {/* Page title */}
      <div className="mb-9 text-center">
        <h1 className="text-[30px] font-semibold tracking-[-0.035em] text-foreground">
          Reset your password
        </h1>

        <p className="mt-2 text-sm text-muted-foreground">
          Enter your email and we&apos;ll send you a link to create a new password.
        </p>
      </div>

      {/* Forgot password card */}
      <div className="rounded-lg border border-white/[0.10] bg-[#050505] p-7 shadow-none">
        <div className="mb-7">
          <h2 className="text-[22px] font-semibold tracking-[-0.025em]">
            Forgot your password?
          </h2>

          <p className="mt-1.5 text-sm text-muted-foreground">
            No problem. Enter your email address below and we&apos;ll send you a link to
            reset it.
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
          {/* Email */}
          <div className="space-y-2">
            <Label htmlFor="email" className="text-sm">
              Email
            </Label>

            <Input
              id="email"
              type="email"
              autoComplete="email"
              autoFocus
              value={email}
              onChange={(e) => {
                setEmail(e.target.value);
                if (errors.email) setErrors((prev) => { const n = { ...prev }; delete n.email; return n; });
              }}
              aria-invalid={!!errors.email}
              aria-describedby={errors.email ? "email-error" : undefined}
              placeholder="you@company.com"
              className="h-11 rounded-md border-white/[0.11] bg-[#080808]"
              disabled={submitting}
            />

            {errors.email && (
              <p id="email-error" className="text-xs text-destructive">
                {errors.email}
              </p>
            )}
          </div>

          {/* Submit */}
          <Button
            type="submit"
            disabled={submitting}
            className="h-11 w-full rounded-md bg-[#f3f3f1] text-sm font-medium text-[#080808] hover:bg-white"
          >
            {submitting ? (
              <>
                <Loader2 className="size-4 animate-spin" />
                Sending reset link
              </>
            ) : (
              "Send reset link"
            )}
          </Button>
        </form>

        {/* Divider */}
        <div className="my-6 flex items-center gap-3">
          <div className="h-px flex-1 bg-white/[0.08]" />
          <span className="text-[11px] text-muted-foreground/60">
            OR
          </span>
          <div className="h-px flex-1 bg-white/[0.08]" />
        </div>

        {/* Back to login */}
        <div className="text-center text-sm text-muted-foreground">
          Remember your password?{" "}
          <Link
            href={`/login${searchParams.get("next") ? `?next=${searchParams.get("next")}` : ""}`}
            className="font-medium text-foreground hover:underline"
          >
            Sign in
          </Link>
        </div>
      </div>
    </div>
  );
}

export default function ForgotPasswordPage() {
  return (
    <Suspense fallback={null}>
      <ForgotPasswordPageContent />
    </Suspense>
  );
}