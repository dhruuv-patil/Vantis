"use client";

import Link from "next/link";
import { FileQuestion } from "lucide-react";
import { Button } from "@/components/ui/button";
import { PageContainer, EmptyState } from "@/components/app/page-primitives";

export default function AppNotFound() {
  return (
    <PageContainer variant="narrow" className="py-16">
      <EmptyState
        icon={FileQuestion}
        title="Page not found"
        description="The resource you are looking for does not exist or has been moved."
        actions={
          <Link href="/dashboard">
            <Button size="sm">Back to Dashboard</Button>
          </Link>
        }
      />
    </PageContainer>
  );
}
