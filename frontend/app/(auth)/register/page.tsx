"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import { Loader2 } from "lucide-react";

import { getErrorMessage, getFieldErrors, register } from "@/lib/api";
import { useAuthStore } from "@/lib/auth-store";
import { signupSchema, toFieldErrors } from "@/lib/validations";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { OAuthButtons } from "@/components/auth/oauth-buttons";

function RegisterPageContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const status = useAuthStore((s) => s.status);
  const next = searchParams.get("next") ?? "/dashboard";

  const [values, setValues] = useState({
    fullName: "",
    email: "",
    password: "",
    confirmPassword: "",
  });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (status === "authenticated") {
      router.replace(next);
    }
  }, [status, router, next]);

  function set(key: keyof typeof values) {
    return (e: React.ChangeEvent<HTMLInputElement>) => {
      setValues((v) => ({ ...v, [key]: e.target.value }));
      if (formError) setFormError(null);
      if (errors[key]) {
        setErrors((prev) => {
          const n = { ...prev };
          delete n[key];
          return n;
        });
      }
    };
  }

  async function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();

    // Guard against duplicate submissions while a request is in flight.
    if (submitting) return;
    setFormError(null);

    const parsed = signupSchema.safeParse(values);

    if (!parsed.success) {
      setErrors(toFieldErrors(parsed.error));
      return;
    }

    setErrors({});
    setSubmitting(true);

    try {
      await register({
        fullName: parsed.data.fullName,
        email: parsed.data.email,
        password: parsed.data.password,
      });
      router.replace(next);
    } catch (err) {
      const fieldErrors = getFieldErrors(err);

      if (Object.keys(fieldErrors).length > 0) {
        setErrors(fieldErrors);
      }

      setFormError(getErrorMessage(err, "Could not create your account."));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="w-full">
      <div className="mb-8 text-center">
        <h1 className="text-[30px] font-semibold tracking-[-0.035em] text-foreground sm:text-[32px]">
          Create your Vantis account
        </h1>

        <p className="mt-2 text-sm text-muted-foreground">
          Start building and running workflows in minutes.
        </p>
      </div>

      <div className="rounded-lg border border-white/[0.10] bg-[#050505] p-7 shadow-none">
        <div className="mb-7">
          <h2 className="text-[22px] font-semibold tracking-[-0.025em]">
            Get started
          </h2>

          <p className="mt-1.5 text-sm text-muted-foreground">
            Set up your credentials to create an account.
          </p>
        </div>

        {formError ? (
          <div
            role="alert"
            className="mb-5 rounded-md border border-red-500/20 bg-red-500/[0.06] px-3.5 py-3 text-sm text-destructive"
          >
            {formError}
          </div>
        ) : null}

        <form onSubmit={onSubmit} noValidate className="space-y-5">
          {/* Name */}
          <div className="space-y-2">
            <Label htmlFor="fullName" className="text-sm">
              Name
            </Label>
            <Input
              id="fullName"
              type="text"
              autoComplete="name"
              autoFocus
              value={values.fullName}
              onChange={set("fullName")}
              aria-invalid={!!errors.fullName}
              aria-describedby={errors.fullName ? "fullName-error" : undefined}
              placeholder="Your name"
              className="h-11 rounded-md border-white/[0.11] bg-[#080808]"
              disabled={submitting}
            />
            {errors.fullName ? (
              <p id="fullName-error" className="text-xs text-destructive">
                {errors.fullName}
              </p>
            ) : null}
          </div>

          {/* Email */}
          <div className="space-y-2">
            <Label htmlFor="email" className="text-sm">
              Email
            </Label>
            <Input
              id="email"
              type="email"
              autoComplete="email"
              value={values.email}
              onChange={set("email")}
              aria-invalid={!!errors.email}
              aria-describedby={errors.email ? "email-error" : undefined}
              placeholder="you@company.com"
              className="h-11 rounded-md border-white/[0.11] bg-[#080808]"
              disabled={submitting}
            />
            {errors.email ? (
              <p id="email-error" className="text-xs text-destructive">
                {errors.email}
              </p>
            ) : null}
          </div>

          {/* Password */}
          <div className="space-y-2">
            <Label htmlFor="password" className="text-sm">
              Password
            </Label>
            <Input
              id="password"
              type="password"
              autoComplete="new-password"
              value={values.password}
              onChange={set("password")}
              aria-invalid={!!errors.password}
              aria-describedby={errors.password ? "password-error" : undefined}
              placeholder="Create a password"
              className="h-11 rounded-md border-white/[0.11] bg-[#080808]"
              disabled={submitting}
            />
            {!errors.password ? (
              <p className="text-[11px] leading-5 text-muted-foreground/55">
                At least 8 characters, with a letter and a number.
              </p>
            ) : null}
            {errors.password ? (
              <p id="password-error" className="text-xs text-destructive">
                {errors.password}
              </p>
            ) : null}
          </div>

          {/* Confirm Password */}
          <div className="space-y-2">
            <Label htmlFor="confirmPassword" className="text-sm">
              Confirm Password
            </Label>
            <Input
              id="confirmPassword"
              type="password"
              autoComplete="new-password"
              value={values.confirmPassword}
              onChange={set("confirmPassword")}
              aria-invalid={!!errors.confirmPassword}
              aria-describedby={errors.confirmPassword ? "confirmPassword-error" : undefined}
              placeholder="Confirm your password"
              className="h-11 rounded-md border-white/[0.11] bg-[#080808]"
              disabled={submitting}
            />
            {errors.confirmPassword ? (
              <p id="confirmPassword-error" className="text-xs text-destructive">
                {errors.confirmPassword}
              </p>
            ) : null}
          </div>

          <Button
            type="submit"
            className="h-11 w-full rounded-md bg-[#f2f2f0] text-sm font-medium text-[#080808] hover:bg-white"
            disabled={submitting}
          >
            {submitting ? (
              <>
                <Loader2 className="size-4 animate-spin" />
                Creating account
              </>
            ) : (
              "Create account"
            )}
          </Button>
        </form>

        <div className="my-6 flex items-center gap-3">
          <div className="h-px flex-1 bg-white/[0.08]" />
          <span className="text-[11px] text-muted-foreground/60">OR</span>
          <div className="h-px flex-1 bg-white/[0.08]" />
        </div>

        {/* OAuth Buttons */}
        <OAuthButtons next={next} disabled={submitting} />

        <p className="mt-6 text-center text-sm text-muted-foreground">
          Already have an account?{" "}
          <Link
            href="/login"
            className="font-medium text-foreground hover:underline"
          >
            Log in
          </Link>
        </p>
      </div>
    </div>
  );
}

export default function RegisterPage() {
  return (
    <Suspense fallback={null}>
      <RegisterPageContent />
    </Suspense>
  );
}
