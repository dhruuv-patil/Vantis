"use client";

import { useEffect } from "react";
import { PageContainer, PageHeader, ErrorState } from "@/components/app/page-primitives";
import { getErrorMessage } from "@/lib/api";

export default function AppError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error("Application error boundary caught:", error);
  }, [error]);

  return (
    <PageContainer variant="narrow">
      <PageHeader
        eyebrow="SYSTEM ERROR"
        title="Something went wrong"
        description="An unexpected error occurred while rendering this page."
      />
      <ErrorState
        title="Application Exception"
        message={getErrorMessage(error, "An unexpected error occurred.")}
        onRetry={() => reset()}
      />
    </PageContainer>
  );
}
