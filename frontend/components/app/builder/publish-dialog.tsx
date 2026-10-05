"use client";

import * as React from "react";
import { Rocket } from "lucide-react";

import type { ValidationResult } from "@/types";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Badge } from "@/components/ui/badge";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

interface PublishDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  pending: boolean;
  /** Set when a publish attempt failed validation — issues to display. */
  blockingValidation: ValidationResult | null;
  onPublish: (note: string) => void;
}

/**
 * Publish confirmation. Collects an optional version note. If a previous
 * attempt was rejected, the blocking validation errors are shown here so the
 * user can see exactly why the publish did not go through.
 */
export function PublishDialog({
  open,
  onOpenChange,
  pending,
  blockingValidation,
  onPublish,
}: PublishDialogProps) {
  const [note, setNote] = React.useState("");

  // Reset the note each time the dialog is opened fresh.
  React.useEffect(() => {
    if (open) setNote("");
  }, [open]);

  const errors =
    blockingValidation?.issues.filter((i) => i.severity === "ERROR") ?? [];

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Publish workflow</DialogTitle>
          <DialogDescription>
            Publishing creates an immutable version from the current graph.
            Validation runs first — a workflow with errors will not publish.
          </DialogDescription>
        </DialogHeader>

        {errors.length > 0 && (
          <div className="space-y-2 rounded-md border border-destructive/40 bg-destructive/10 p-3">
            <p className="flex items-center gap-2 text-sm font-medium text-destructive">
              <Badge variant="destructive">{errors.length} error(s)</Badge>
              Fix these before publishing
            </p>
            <ul className="list-inside list-disc space-y-1 text-xs text-destructive">
              {errors.map((issue, i) => (
                <li key={`${issue.code}-${i}`}>{issue.message}</li>
              ))}
            </ul>
          </div>
        )}

        <div className="space-y-1.5">
          <Label htmlFor="publish-note">Version note (optional)</Label>
          <Textarea
            id="publish-note"
            value={note}
            rows={3}
            placeholder="What changed in this version?"
            onChange={(e) => setNote(e.target.value)}
          />
        </div>

        <DialogFooter>
          <Button
            variant="ghost"
            onClick={() => onOpenChange(false)}
            disabled={pending}
          >
            Cancel
          </Button>
          <Button
            variant="default"
            onClick={() => onPublish(note.trim())}
            disabled={pending}
          >
            <Rocket className="size-4" />
            {pending ? "Publishing…" : "Publish"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
