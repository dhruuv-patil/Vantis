"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { Loader2, Mail } from "lucide-react";

import { getErrorCode, resendVerification } from "@/lib/api";
import { Button } from "@/components/ui/button";

type ResendState = "idle" | "sending" | "sent" | "rate_limited" | "error";

function CheckEmailPageContent() {
  const searchParams = useSearchParams();
  const email = searchParams.get("email") ?? "";

  const [resendState, setResendState] = useState<ResendState>("idle");

  async function handleResend() {
    if (resendState === "sending" || resendState === "sent") return;
    setResendState("sending");

    try {
      await resendVerification(email);
      setResendState("sent");
    } catch (err) {
      const code = getErrorCode(err);
      if (code === "RATE_LIMITED") {
        setResendState("rate_limited");
      } else {
        setResendState("error");
      }
    }
  }

  return (
    <div className="w-full">
      <div className="mb-8 text-center">
        <h1 className="text-[30px] font-semibold tracking-[-0.035em] text-foreground sm:text-[32px]">
          Check your inbox
        </h1>
        <p className="mt-2 text-sm text-muted-foreground">
          One more step before you can sign in.
        </p>
      </div>

      <div className="rounded-lg border border-border dark:border-white/[0.10] bg-card dark:bg-[#050505] p-7 shadow-sm dark:shadow-none">
        {/* Icon */}
        <div className="mb-6 flex justify-center">
          <div className="flex size-14 items-center justify-center rounded-full border border-border dark:border-white/[0.10] bg-muted/40">
            <Mail className="size-6 text-muted-foreground" />
          </div>
        </div>

        <p className="text-center text-sm text-muted-foreground">
          We sent a verification link to
        </p>
        {email && (
          <p className="mt-1 text-center text-sm font-medium text-foreground break-all">
            {email}
          </p>
        )}
        <p className="mt-3 text-center text-sm text-muted-foreground">
          Click the link in the email to verify your address and activate your
          account. The link expires in&nbsp;24&nbsp;hours.
        </p>

        {/* Resend feedback */}
        {resendState === "sent" && (
          <div
            role="status"
            className="mt-5 rounded-md border border-green-500/20 bg-green-500/[0.06] px-3.5 py-3 text-center text-sm text-green-700 dark:text-green-400"
          >
            Verification email resent — please check your inbox.
          </div>
        )}

        {resendState === "rate_limited" && (
          <div
            role="alert"
            className="mt-5 rounded-md border border-amber-500/20 bg-amber-500/[0.06] px-3.5 py-3 text-center text-sm text-amber-700 dark:text-amber-400"
          >
            Too many requests. Please wait a moment before trying again.
          </div>
        )}

        {resendState === "error" && (
          <div
            role="alert"
            className="mt-5 rounded-md border border-red-500/20 bg-red-500/[0.06] px-3.5 py-3 text-center text-sm text-destructive"
          >
            Something went wrong. Please try again or contact support.
          </div>
        )}

        {/* Resend button */}
        {resendState !== "sent" && (
          <Button
            variant="outline"
            className="mt-6 w-full h-11 rounded-md text-sm"
            onClick={handleResend}
            disabled={resendState === "sending"}
          >
            {resendState === "sending" ? (
              <>
                <Loader2 className="size-4 animate-spin" />
                Resending…
              </>
            ) : (
              "Resend verification email"
            )}
          </Button>
        )}

        <p className="mt-6 text-center text-xs text-muted-foreground">
          Already verified?{" "}
          <Link
            href="/login"
            className="font-medium text-foreground hover:underline"
          >
            Sign in
          </Link>
        </p>

        <p className="mt-2 text-center text-xs text-muted-foreground">
          Wrong email address?{" "}
          <Link
            href="/register"
            className="font-medium text-foreground hover:underline"
          >
            Start over
          </Link>
        </p>
      </div>
    </div>
  );
}

export default function CheckEmailPage() {
  return (
    <Suspense fallback={null}>
      <CheckEmailPageContent />
    </Suspense>
  );
}
