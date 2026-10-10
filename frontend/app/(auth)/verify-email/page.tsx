"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useRef, useState } from "react";
import { CheckCircle, Loader2, XCircle } from "lucide-react";

import { getErrorCode, verifyEmail } from "@/lib/api";
import { Button } from "@/components/ui/button";

type VerifyState = "verifying" | "success" | "invalid" | "expired" | "error";

function VerifyEmailPageContent() {
  const searchParams = useSearchParams();
  const token = searchParams.get("token") ?? "";

  const [state, setState] = useState<VerifyState>("verifying");
  // Prevent double-submission in React Strict Mode.
  const attempted = useRef(false);

  useEffect(() => {
    if (attempted.current) return;
    attempted.current = true;

    if (!token) {
      setState("invalid");
      return;
    }

    verifyEmail(token)
      .then(() => setState("success"))
      .catch((err) => {
        const code = getErrorCode(err);
        if (code === "VERIFICATION_TOKEN_INVALID") {
          // The backend returns this for expired, consumed, and unknown tokens.
          // We distinguish expired via a query param set by the email link
          // (see EMAIL_VERIFICATION_URL_TEMPLATE in backend), but fall back to
          // a generic invalid state when no hint is present.
          const hint = searchParams.get("hint");
          setState(hint === "expired" ? "expired" : "invalid");
        } else {
          setState("error");
        }
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (state === "verifying") {
    return (
      <div className="w-full">
        <div className="rounded-lg border border-border dark:border-white/[0.10] bg-card dark:bg-[#050505] p-10 shadow-sm dark:shadow-none flex flex-col items-center gap-4">
          <Loader2 className="size-8 animate-spin text-muted-foreground" />
          <p className="text-sm text-muted-foreground">
            Verifying your email address…
          </p>
        </div>
      </div>
    );
  }

  if (state === "success") {
    return (
      <div className="w-full">
        <div className="mb-8 text-center">
          <h1 className="text-[30px] font-semibold tracking-[-0.035em] text-foreground sm:text-[32px]">
            Email verified
          </h1>
          <p className="mt-2 text-sm text-muted-foreground">
            Your account is ready. Sign in to get started.
          </p>
        </div>

        <div className="rounded-lg border border-border dark:border-white/[0.10] bg-card dark:bg-[#050505] p-7 shadow-sm dark:shadow-none">
          <div className="mb-6 flex justify-center">
            <div className="flex size-14 items-center justify-center rounded-full border border-green-500/20 bg-green-500/[0.06]">
              <CheckCircle className="size-6 text-green-600 dark:text-green-400" />
            </div>
          </div>

          <p className="text-center text-sm text-muted-foreground">
            Your email address has been successfully verified. You can now sign
            in and start using Vantis.
          </p>

          <Button
            asChild
            className="mt-6 w-full h-11 rounded-md bg-foreground text-background hover:bg-foreground/90 dark:bg-[#f2f2f0] dark:text-[#080808] dark:hover:bg-white text-sm font-medium"
          >
            <Link href="/login">Sign in to Vantis</Link>
          </Button>
        </div>
      </div>
    );
  }

  const isExpired = state === "expired";

  return (
    <div className="w-full">
      <div className="mb-8 text-center">
        <h1 className="text-[30px] font-semibold tracking-[-0.035em] text-foreground sm:text-[32px]">
          {isExpired ? "Link expired" : "Invalid link"}
        </h1>
        <p className="mt-2 text-sm text-muted-foreground">
          {isExpired
            ? "This verification link has expired."
            : "This verification link is invalid or has already been used."}
        </p>
      </div>

      <div className="rounded-lg border border-border dark:border-white/[0.10] bg-card dark:bg-[#050505] p-7 shadow-sm dark:shadow-none">
        <div className="mb-6 flex justify-center">
          <div className="flex size-14 items-center justify-center rounded-full border border-red-500/20 bg-red-500/[0.06]">
            <XCircle className="size-6 text-destructive" />
          </div>
        </div>

        <p className="text-center text-sm text-muted-foreground">
          {isExpired
            ? "Verification links are valid for 24 hours. Request a new one to verify your email address."
            : "This link may have already been used or the URL may have been modified. Request a new verification email if you need to verify your account."}
        </p>

        <Button
          asChild
          variant="outline"
          className="mt-6 w-full h-11 rounded-md text-sm"
        >
          <Link href="/check-email">Request a new verification email</Link>
        </Button>

        <p className="mt-4 text-center text-xs text-muted-foreground">
          Already verified?{" "}
          <Link
            href="/login"
            className="font-medium text-foreground hover:underline"
          >
            Sign in
          </Link>
        </p>
      </div>
    </div>
  );
}

export default function VerifyEmailPage() {
  return (
    <Suspense fallback={null}>
      <VerifyEmailPageContent />
    </Suspense>
  );
}
