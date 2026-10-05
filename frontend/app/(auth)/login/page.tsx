"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import { Loader2 } from "lucide-react";

import { getErrorMessage, getFieldErrors, login } from "@/lib/api";
import { useAuthStore } from "@/lib/auth-store";
import { loginSchema, toFieldErrors } from "@/lib/validations";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { OAuthButtons } from "@/components/auth/oauth-buttons";

function LoginPageContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const status = useAuthStore((s) => s.status);
  const next = searchParams.get("next") ?? "/dashboard";
  const urlError = searchParams.get("error");

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(() => {
    if (urlError === "oauth_failed") {
      return "OAuth authentication could not be completed. Please try again.";
    }
    if (urlError === "email_not_verified") {
      return "Your OAuth account must have a verified email address to sign in.";
    }
    if (urlError) {
      return "Authentication failed. Please check your credentials and try again.";
    }
    return null;
  });
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (status === "authenticated") {
      router.replace(next);
    }
  }, [status, router, next]);

  async function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();

    // Guard against duplicate submissions while a request is in flight.
    if (submitting) return;
    setFormError(null);

    const parsed = loginSchema.safeParse({ email, password });

    if (!parsed.success) {
      setErrors(toFieldErrors(parsed.error));
      return;
    }

    setErrors({});
    setSubmitting(true);

    try {
      await login(parsed.data);
      router.replace(next);
    } catch (err) {
      const fieldErrors = getFieldErrors(err);

      if (Object.keys(fieldErrors).length > 0) {
        setErrors(fieldErrors);
      }

      setFormError(getErrorMessage(err, "Invalid email or password."));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="w-full">
      {/* Page title */}
      <div className="mb-9 text-center">
        <h1 className="text-[30px] font-semibold tracking-[-0.035em] text-foreground">
          Sign in to Vantis
        </h1>

        <p className="mt-2 text-sm text-muted-foreground">
          Access your workflows and operational workspace.
        </p>
      </div>

      {/* Login card */}
      <div className="rounded-lg border border-white/[0.10] bg-[#050505] p-7 shadow-none">
        <div className="mb-7">
          <h2 className="text-[22px] font-semibold tracking-[-0.025em]">
            Welcome back
          </h2>

          <p className="mt-1.5 text-sm text-muted-foreground">
            Enter your credentials to access your account.
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
                if (formError) setFormError(null);
                if (errors.email)
                  setErrors((prev) => {
                    const n = { ...prev };
                    delete n.email;
                    return n;
                  });
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

          {/* Password */}
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <Label htmlFor="password" className="text-sm">
                Password
              </Label>

              <Link
                href="/forgot-password"
                className="text-xs text-muted-foreground transition-colors hover:text-foreground"
              >
                Forgot password?
              </Link>
            </div>

            <Input
              id="password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => {
                setPassword(e.target.value);
                if (formError) setFormError(null);
                if (errors.password)
                  setErrors((prev) => {
                    const n = { ...prev };
                    delete n.password;
                    return n;
                  });
              }}
              aria-invalid={!!errors.password}
              aria-describedby={
                errors.password ? "password-error" : undefined
              }
              placeholder="Enter your password"
              className="h-11 rounded-md border-white/[0.11] bg-[#080808]"
              disabled={submitting}
            />

            {errors.password && (
              <p id="password-error" className="text-xs text-destructive">
                {errors.password}
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
                Logging in
              </>
            ) : (
              "Log in"
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

        {/* OAuth Buttons */}
        <OAuthButtons next={next} disabled={submitting} />

        {/* Switch to Signup */}
        <div className="mt-6 text-center text-sm text-muted-foreground">
          Don&apos;t have an account?{" "}
          <Link
            href="/signup"
            className="font-medium text-foreground hover:underline"
          >
            Sign up
          </Link>
        </div>
      </div>
    </div>
  );
}

export default function LoginPage() {
  return (
    <Suspense fallback={null}>
      <LoginPageContent />
    </Suspense>
  );
}