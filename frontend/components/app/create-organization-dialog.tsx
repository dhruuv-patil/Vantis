"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2 } from "lucide-react";
import { toast } from "sonner";

import { createOrganization, getErrorMessage, getFieldErrors, switchOrganization } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

interface CreateOrganizationDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function CreateOrganizationDialog({
  open,
  onOpenChange,
}: CreateOrganizationDialogProps) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [name, setName] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});

  const mutation = useMutation({
    mutationFn: async () => {
      const trimmed = name.trim();
      const created = await createOrganization({ organizationName: trimmed });
      // Automatically switch into the newly created organization
      await switchOrganization(created.organization.id);
      return created;
    },
    onSuccess: (data) => {
      // Invalidate and clear all queries so previous tenant's data does not linger
      queryClient.clear();
      toast.success(`Created and switched to ${data.organization.name}.`);
      setName("");
      setErrors({});
      onOpenChange(false);
      router.refresh();
    },
    onError: (err) => {
      const fieldErrors = getFieldErrors(err);
      if (Object.keys(fieldErrors).length > 0) {
        setErrors(fieldErrors);
        return;
      }
      toast.error(
        getErrorMessage(err, "Could not create workspace. Please try again."),
      );
    },
  });

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    setErrors({});
    const trimmed = name.trim();
    if (!trimmed) {
      setErrors({ organizationName: "Workspace name is required." });
      return;
    }
    if (trimmed.length < 2 || trimmed.length > 80) {
      setErrors({
        organizationName: "Workspace name must be between 2 and 80 characters.",
      });
      return;
    }
    mutation.mutate();
  }

  function handleOpenChange(next: boolean) {
    if (!next) {
      setName("");
      setErrors({});
      mutation.reset();
    }
    onOpenChange(next);
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-md">
        <form onSubmit={onSubmit}>
          <DialogHeader className="pr-8">
            <DialogTitle className="text-foreground dark:text-white/90">
              Create workspace
            </DialogTitle>
            <DialogDescription className="text-muted-foreground dark:text-white/40">
              Create a new organization workspace. You will be set as the Owner
              and can invite team members and configure integrations.
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-4 py-4">
            <div className="space-y-2">
              <Label htmlFor="workspace-name">Workspace name</Label>
              <Input
                id="workspace-name"
                autoFocus
                type="text"
                placeholder="Vantis"
                value={name}
                onChange={(e) => {
                  setName(e.target.value);
                  if (errors.organizationName) {
                    setErrors({});
                  }
                }}
                disabled={mutation.isPending}
                aria-invalid={Boolean(errors.organizationName)}
              />
              {errors.organizationName && (
                <p className="text-xs text-destructive">
                  {errors.organizationName}
                </p>
              )}
            </div>
          </div>

          <DialogFooter className="gap-2 sm:gap-0">
            <Button
              type="button"
              variant="ghost"
              onClick={() => handleOpenChange(false)}
              disabled={mutation.isPending}
            >
              Cancel
            </Button>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending && (
                <Loader2 className="mr-2 size-4 animate-spin" />
              )}
              {mutation.isPending ? "Creating..." : "Create workspace"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
