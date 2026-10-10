"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, ArrowRight, CreditCard, Loader2, Plug, Users } from "lucide-react";
import { toast } from "sonner";

import {
  changePassword,
  getErrorMessage,
  getFieldErrors,
  renameCurrentOrganization,
} from "@/lib/api";
import { hasRole, useAuthStore } from "@/lib/auth-store";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Separator } from "@/components/ui/separator";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  PageContainer,
  PageHeader,
} from "@/components/app/page-primitives";
import { ThemeToggle } from "@/components/theme-toggle";

export default function SettingsPage() {
  return (
    <PageContainer variant="narrow">
      <PageHeader
        eyebrow="Organization"
        title="Settings"
        description="Manage your appearance theme, organization, and account security."
      />

      <div className="space-y-8">
        <AppearanceSettings />
        <Separator className="border-border dark:border-white/[0.065]" />
        <GeneralSettings />
        <Separator className="border-border dark:border-white/[0.065]" />
        <OrganizationResources />
        <Separator className="border-border dark:border-white/[0.065]" />
        <SecuritySettings />
        <Separator className="border-border dark:border-white/[0.065]" />
        <DangerZone />
      </div>
    </PageContainer>
  );
}

/* -------------------------------------------------------------- appearance */

function AppearanceSettings() {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Appearance & Theme</CardTitle>
        <CardDescription>
          Customize the visual appearance of the application. Toggle between dark and light modes.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="space-y-0.5">
            <Label className="text-sm font-medium">Color theme</Label>
            <p className="text-xs text-muted-foreground">
              Select your preferred color theme across dashboards, builder, templates, and integrations.
            </p>
          </div>
          <ThemeToggle variant="segmented" />
        </div>
      </CardContent>
    </Card>
  );
}

/* ------------------------------------------------------------------ general */

function GeneralSettings() {
  const org = useAuthStore((s) => s.currentOrganization);
  const role = useAuthStore((s) => s.currentRole);
  const setCurrentOrganization = useAuthStore((s) => s.setCurrentOrganization);
  const isAdmin = hasRole(role, "ADMIN");
  const queryClient = useQueryClient();

  const [name, setName] = useState(org?.name ?? "");
  const [error, setError] = useState<string | null>(null);

  // Keep the field in sync if the org changes underneath us (e.g. org switch).
  useEffect(() => {
    setName(org?.name ?? "");
  }, [org?.id, org?.name]);

  const mutation = useMutation({
    mutationFn: () => renameCurrentOrganization(name.trim()),
    onSuccess: (data) => {
      setCurrentOrganization(data.organization);
      queryClient.invalidateQueries({ queryKey: ["organizations"] });
      toast.success("Organization renamed.");
    },
    onError: (err) => {
      const fieldErrors = getFieldErrors(err);
      setError(
        fieldErrors.name ?? getErrorMessage(err, "Could not rename the organization."),
      );
    },
  });

  const dirty = name.trim() !== (org?.name ?? "") && name.trim().length > 0;

  return (
    <Card>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          setError(null);
          if (dirty) mutation.mutate();
        }}
      >
        <CardHeader>
          <CardTitle>General</CardTitle>
          <CardDescription>
            The organization name is shown throughout the app. Its slug is
            permanent and does not change on rename.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="org-name">Organization name</Label>
            <Input
              id="org-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              disabled={!isAdmin}
              aria-invalid={!!error}
            />
            {error && <p className="text-xs text-destructive">{error}</p>}
            {!isAdmin && (
              <p className="text-xs text-muted-foreground">
                Only an admin or owner can rename the organization.
              </p>
            )}
          </div>
          {org?.slug && (
            <div className="space-y-2">
              <Label>Slug</Label>
              <p className="font-mono text-sm text-muted-foreground">{org.slug}</p>
            </div>
          )}
        </CardContent>
        <CardFooter className="justify-end">
          <Button type="submit" disabled={!isAdmin || !dirty || mutation.isPending}>
            {mutation.isPending && <Loader2 className="size-4 animate-spin" />}
            Save changes
          </Button>
        </CardFooter>
      </form>
    </Card>
  );
}

/* ----------------------------------------------------------------- security */

function SecuritySettings() {
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});

  const mutation = useMutation({
    mutationFn: () => changePassword({ currentPassword, newPassword }),
    onSuccess: () => {
      toast.success("Password changed. Other devices have been signed out.");
      setCurrentPassword("");
      setNewPassword("");
      setConfirmPassword("");
      setErrors({});
    },
    onError: (err) => {
      const fieldErrors = getFieldErrors(err);
      if (Object.keys(fieldErrors).length > 0) {
        setErrors(fieldErrors);
      } else {
        // INVALID_PASSWORD (wrong current password) carries no field errors.
        setErrors({
          currentPassword: getErrorMessage(err, "Could not change the password."),
        });
      }
    },
  });

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    const next: Record<string, string> = {};
    if (!currentPassword) next.currentPassword = "Enter your current password.";
    if (!newPassword) next.newPassword = "Enter a new password.";
    if (newPassword && newPassword !== confirmPassword) {
      next.confirmPassword = "Passwords do not match.";
    }
    setErrors(next);
    if (Object.keys(next).length > 0) return;
    mutation.mutate();
  }

  return (
    <Card>
      <form onSubmit={onSubmit}>
        <CardHeader>
          <CardTitle>Change password</CardTitle>
          <CardDescription>
            Choose a strong password of 8–72 characters with at least one letter
            and one number. Changing it signs out your other devices.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="current-password">Current password</Label>
            <Input
              id="current-password"
              type="password"
              autoComplete="current-password"
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
              aria-invalid={!!errors.currentPassword}
            />
            {errors.currentPassword && (
              <p className="text-xs text-destructive">{errors.currentPassword}</p>
            )}
          </div>
          <div className="space-y-2">
            <Label htmlFor="new-password">New password</Label>
            <Input
              id="new-password"
              type="password"
              autoComplete="new-password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              aria-invalid={!!errors.newPassword}
            />
            {errors.newPassword && (
              <p className="text-xs text-destructive">{errors.newPassword}</p>
            )}
          </div>
          <div className="space-y-2">
            <Label htmlFor="confirm-password">Confirm new password</Label>
            <Input
              id="confirm-password"
              type="password"
              autoComplete="new-password"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              aria-invalid={!!errors.confirmPassword}
            />
            {errors.confirmPassword && (
              <p className="text-xs text-destructive">{errors.confirmPassword}</p>
            )}
          </div>
        </CardContent>
        <CardFooter className="justify-end">
          <Button type="submit" disabled={mutation.isPending}>
            {mutation.isPending && <Loader2 className="size-4 animate-spin" />}
            Update password
          </Button>
        </CardFooter>
      </form>
    </Card>
  );
}

/* ------------------------------------------------ organization resources */

function OrganizationResources() {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Workspace Areas</CardTitle>
        <CardDescription>
          Quick access to tenant-scoped resources, billing configurations, and team roles.
        </CardDescription>
      </CardHeader>
      <CardContent className="grid gap-3 sm:grid-cols-3">
        <Link
          href="/team"
          className="group flex flex-col justify-between rounded-lg border border-border dark:border-white/[0.08] bg-muted/20 dark:bg-white/[0.02] p-4 transition-colors hover:bg-muted/50 dark:hover:bg-white/[0.05]"
        >
          <div className="space-y-1">
            <div className="flex items-center gap-2 text-sm font-medium text-foreground dark:text-white/90">
              <Users className="size-4 text-muted-foreground" />
              <span>Team & Members</span>
            </div>
            <p className="text-xs text-muted-foreground">
              Invite teammates, manage roles, and review pending invitations.
            </p>
          </div>
          <div className="mt-3 flex items-center gap-1 text-xs text-primary group-hover:underline">
            <span>Manage team</span>
            <ArrowRight className="size-3" />
          </div>
        </Link>

        <Link
          href="/billing"
          className="group flex flex-col justify-between rounded-lg border border-border dark:border-white/[0.08] bg-muted/20 dark:bg-white/[0.02] p-4 transition-colors hover:bg-muted/50 dark:hover:bg-white/[0.05]"
        >
          <div className="space-y-1">
            <div className="flex items-center gap-2 text-sm font-medium text-foreground dark:text-white/90">
              <CreditCard className="size-4 text-muted-foreground" />
              <span>Billing & Plan</span>
            </div>
            <p className="text-xs text-muted-foreground">
              Subscription tiers, usage quotas, currency, and invoices.
            </p>
          </div>
          <div className="mt-3 flex items-center gap-1 text-xs text-primary group-hover:underline">
            <span>View billing</span>
            <ArrowRight className="size-3" />
          </div>
        </Link>

        <Link
          href="/integrations"
          className="group flex flex-col justify-between rounded-lg border border-border dark:border-white/[0.08] bg-muted/20 dark:bg-white/[0.02] p-4 transition-colors hover:bg-muted/50 dark:hover:bg-white/[0.05]"
        >
          <div className="space-y-1">
            <div className="flex items-center gap-2 text-sm font-medium text-foreground dark:text-white/90">
              <Plug className="size-4 text-muted-foreground" />
              <span>Integrations</span>
            </div>
            <p className="text-xs text-muted-foreground">
              Connected providers, credentials, and webhook endpoints.
            </p>
          </div>
          <div className="mt-3 flex items-center gap-1 text-xs text-primary group-hover:underline">
            <span>Configure</span>
            <ArrowRight className="size-3" />
          </div>
        </Link>
      </CardContent>
    </Card>
  );
}

/* ---------------------------------------------------------- danger zone */

function DangerZone() {
  const role = useAuthStore((s) => s.currentRole);
  const org = useAuthStore((s) => s.currentOrganization);
  const isOwner = role === "OWNER";

  return (
    <Card className="border-destructive/30 dark:border-red-500/20">
      <CardHeader>
        <div className="flex items-center gap-2 text-destructive">
          <AlertTriangle className="size-4" />
          <CardTitle className="text-destructive">Danger Zone</CardTitle>
        </div>
        <CardDescription>
          Actions that permanently impact access to this workspace and its resources.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between rounded-lg border border-border dark:border-white/[0.08] p-3 text-xs">
          <div className="space-y-0.5">
            <p className="font-medium text-foreground dark:text-white/90">
              Transfer Ownership or Leave Workspace
            </p>
            <p className="text-muted-foreground">
              Each workspace requires at least one active Owner. To transfer ownership,
              promote a team member to Owner in the Team settings before removing your membership.
            </p>
          </div>
          <Link href="/team">
            <Button variant="outline" size="sm" className="shrink-0 text-xs">
              Go to Team
            </Button>
          </Link>
        </div>

        {!isOwner && (
          <p className="text-xs text-muted-foreground">
            Administrative danger zone controls are available exclusively to organization Owners.
          </p>
        )}
      </CardContent>
    </Card>
  );
}
