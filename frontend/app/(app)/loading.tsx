"use client";

import { PageContainer, TableSkeleton } from "@/components/app/page-primitives";

export default function Loading() {
  return (
    <PageContainer variant="wide" className="space-y-6">
      <div className="space-y-2">
        <div className="h-4 w-28 animate-pulse rounded bg-white/[0.06]" />
        <div className="h-8 w-64 animate-pulse rounded bg-white/[0.08]" />
        <div className="h-4 w-96 max-w-full animate-pulse rounded bg-white/[0.04]" />
      </div>
      <TableSkeleton rows={6} />
    </PageContainer>
  );
}
