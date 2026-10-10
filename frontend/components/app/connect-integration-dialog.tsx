"use client";

import { useEffect, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import {
  AlertCircle,
  ExternalLink,
  Eye,
  EyeOff,
  KeyRound,
  Loader2,
} from "lucide-react";
import { toast } from "sonner";
import { ProviderIcon } from "@/components/integrations/provider-icon";

import {
  connectIntegration,
  connectProvider,
  fetchProviders,
  getErrorMessage,
  getFieldErrors,
} from "@/lib/api";
import {
  getProviderDocsLabel,
  getProviderDocsUrl,
  getProviderSetupGuide,
} from "@/lib/provider-meta";
import type { ProviderInfo } from "@/types";

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

interface ConnectIntegrationDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  providerType: string | null;
}

export function ConnectIntegrationDialog({
  open,
  onOpenChange,
  providerType,
}: ConnectIntegrationDialogProps) {
  const queryClient = useQueryClient();

  const [selectedProvider, setSelectedProvider] =
    useState<ProviderInfo | null>(null);
  const [instanceName, setInstanceName] = useState("");
  const [providerConfig, setProviderConfig] = useState<Record<string, string>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [generalError, setGeneralError] = useState<string | null>(null);
  const [loadingProvider, setLoadingProvider] = useState(false);
  const [visibleSecrets, setVisibleSecrets] = useState<Record<string, boolean>>({});

  const normalizedType = providerType ? providerType.toLowerCase().trim() : null;

  /*
   * Resolve documentation URL, label, and step-by-step setup guide
   */
  const docsUrl = selectedProvider?.docsUrl || (normalizedType ? getProviderDocsUrl(normalizedType) : "");
  const docsLabel = normalizedType ? getProviderDocsLabel(normalizedType) : "API Documentation";
  const setupGuideText = selectedProvider?.setupGuide || (normalizedType ? getProviderSetupGuide(normalizedType) : "");
  const setupSteps = setupGuideText
    ? setupGuideText
        .split("\n")
        .map((s) => s.trim())
        .filter((s) => s.length > 0)
    : [];

  /*
   * When the dialog opens for any provider (Slack, n8n, GitHub, OpenAI, etc.),
   * load its exact credential fields from the provider catalog.
   */
  useEffect(() => {
    if (!open || !normalizedType) {
      setSelectedProvider(null);
      setInstanceName("");
      setProviderConfig({});
      setErrors({});
      setGeneralError(null);
      setVisibleSecrets({});
      return;
    }

    let cancelled = false;

    async function loadProvider() {
      const currentTargetType = normalizedType;
      if (!currentTargetType) return;

      setLoadingProvider(true);
      setErrors({});
      setGeneralError(null);

      try {
        const response = await fetchProviders();
        if (cancelled) return;

        const provider = response.providers.find((item) => {
          const itemType = item.type.toLowerCase();
          const target = currentTargetType.toLowerCase();
          return (
            itemType === target ||
            itemType.replace(/-/g, "_") === target.replace(/-/g, "_")
          );
        });

        if (!provider) {
          const typeName = currentTargetType;
          // If not in catalog, construct fallback provider definition
          setSelectedProvider({
            type: typeName as any,
            name: typeName.charAt(0).toUpperCase() + typeName.slice(1),
            description: `Connect to ${typeName}.`,
            available: true,
            capabilities: {
              workflowDiscovery: false,
              executionHistory: false,
              nodeExecutionData: false,
              incrementalSync: false,
              webhooks: false,
              tracing: false,
            },
            credentialFields: [
              {
                key: "apiKey",
                label: "API Key / Token",
                secret: true,
                placeholder: "Enter credentials...",
                example: "",
                help: "Enter the required API secret or token.",
              },
            ],
          });
          return;
        }

        setSelectedProvider(provider);

        const initialConfig: Record<string, string> = {};
        provider.credentialFields.forEach((field) => {
          initialConfig[field.key] = "";
        });
        setProviderConfig(initialConfig);
      } catch (err) {
        if (!cancelled) {
          setGeneralError(
            getErrorMessage(err, "Could not load provider configuration."),
          );
        }
      } finally {
        if (!cancelled) {
          setLoadingProvider(false);
        }
      }
    }

    void loadProvider();

    return () => {
      cancelled = true;
    };
  }, [open, normalizedType]);

  const providerMutation = useMutation({
    mutationFn: async () => {
      if (!selectedProvider) {
        throw new Error("Provider configuration is unavailable.");
      }

      const finalName = instanceName.trim() || selectedProvider.name;

      // If Slack and only webhookUrl is provided, try provider connect first
      try {
        return await connectProvider({
          type: selectedProvider.type,
          name: finalName,
          config: providerConfig,
        });
      } catch (err) {
        // Fallback for Slack legacy endpoint if provider endpoint was rejected
        if (selectedProvider.type.toLowerCase() === "slack" && providerConfig.webhookUrl) {
          return await connectIntegration({
            type: "slack",
            webhookUrl: providerConfig.webhookUrl.trim(),
          });
        }
        throw err;
      }
    },

    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ["integrations"],
      });
      queryClient.invalidateQueries({
        queryKey: ["integration-providers"],
      });

      toast.success(
        `${selectedProvider?.name ?? "Provider"} connected successfully.`,
      );

      setProviderConfig({});
      setSelectedProvider(null);
      setInstanceName("");
      setErrors({});
      setGeneralError(null);

      onOpenChange(false);
    },

    onError: (err) => {
      const fieldErrors = getFieldErrors(err);
      const message = getErrorMessage(
        err,
        `Could not connect ${selectedProvider?.name ?? "provider"}.`,
      );

      if (Object.keys(fieldErrors).length > 0) {
        setErrors(fieldErrors);
      }
      setGeneralError(message);
      toast.error(message);
    },
  });

  function updateProviderField(key: string, value: string) {
    setProviderConfig((current) => ({
      ...current,
      [key]: value,
    }));

    if (errors[key]) {
      setErrors((current) => {
        const next = { ...current };
        delete next[key];
        return next;
      });
    }

    if (generalError) {
      setGeneralError(null);
    }
  }

  function toggleSecretVisibility(key: string) {
    setVisibleSecrets((curr) => ({
      ...curr,
      [key]: !curr[key],
    }));
  }

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setErrors({});
    setGeneralError(null);

    if (!selectedProvider) {
      setGeneralError("Provider configuration is not available.");
      return;
    }

    // Client-side validation: ensure at least one credential field is non-empty
    const hasValue = selectedProvider.credentialFields.some(
      (field) => (providerConfig[field.key] ?? "").trim().length > 0,
    );

    if (!hasValue) {
      const newErrors: Record<string, string> = {};
      selectedProvider.credentialFields.forEach((field) => {
        if (field.secret) {
          newErrors[field.key] = `${field.label} is required.`;
        }
      });
      setErrors(newErrors);
      setGeneralError("Please enter your credentials to connect.");
      return;
    }

    providerMutation.mutate();
  }

  function handleOpenChange(nextOpen: boolean) {
    if (!nextOpen) {
      setProviderConfig({});
      setSelectedProvider(null);
      setInstanceName("");
      setErrors({});
      setGeneralError(null);
      setVisibleSecrets({});
    }

    onOpenChange(nextOpen);
  }

  const isSubmitting = providerMutation.isPending;

  const title = selectedProvider
    ? `Connect ${selectedProvider.name}`
    : normalizedType
      ? `Connect ${normalizedType.charAt(0).toUpperCase() + normalizedType.slice(1)}`
      : "Connect integration";

  const description =
    selectedProvider?.description ??
    "Enter the credentials required to connect this service to FlowOps.";

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="border-border dark:border-white/[0.08] bg-card dark:bg-[#0a0a0c] max-w-lg max-h-[90vh] overflow-y-auto">
        <form onSubmit={onSubmit} className="space-y-5">
          <DialogHeader className="space-y-3 pb-2 border-b border-border dark:border-white/[0.06]">
            <div className="flex items-center gap-3.5">
              <div className="flex size-11 items-center justify-center rounded-xl border border-border dark:border-white/[0.08] bg-muted/50 dark:bg-white/[0.04] shadow-sm">
                <ProviderIcon
                  provider={normalizedType || "generic"}
                  name={selectedProvider?.name || normalizedType || ""}
                  icon={selectedProvider?.icon}
                  className="size-6"
                  theme="auto"
                  fallbackInitials
                />
              </div>

              <div className="space-y-0.5">
                <DialogTitle className="text-base font-semibold text-foreground dark:text-white/90">
                  {title}
                </DialogTitle>
                <DialogDescription className="text-xs text-muted-foreground dark:text-white/45 leading-relaxed">
                  {description}
                </DialogDescription>
              </div>
            </div>
          </DialogHeader>

          {/* Guide Card: Direct URL to get APIs and step-by-step connection guide */}
          <div className="rounded-xl border border-primary/20 bg-primary/[0.03] dark:bg-primary/[0.02] p-4 space-y-3">
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2 text-xs font-semibold text-foreground dark:text-white/90">
                <KeyRound className="size-4 text-primary" />
                <span>How to get credentials & connect</span>
              </div>

              {docsUrl && (
                <a
                  href={docsUrl}
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center gap-1.5 rounded-md border border-primary/30 bg-primary/10 px-2.5 py-1 text-[11px] font-medium text-primary hover:bg-primary/20 transition-colors"
                >
                  <span>{docsLabel}</span>
                  <ExternalLink className="size-3" />
                </a>
              )}
            </div>

            {setupSteps.length > 0 && (
              <ol className="space-y-1.5 text-xs text-muted-foreground dark:text-white/60">
                {setupSteps.map((step, idx) => (
                  <li key={idx} className="flex items-start gap-2.5 leading-relaxed">
                    <span className="flex size-4 shrink-0 items-center justify-center rounded-full bg-primary/15 text-[10px] font-semibold text-primary mt-0.5">
                      {idx + 1}
                    </span>
                    <span>{step.replace(/^\d+\.\s*/, "")}</span>
                  </li>
                ))}
              </ol>
            )}
          </div>

          {/* Inline Error Banner */}
          {generalError && (
            <div className="rounded-lg border border-destructive/30 bg-destructive/10 p-3.5 text-xs text-destructive dark:text-red-300 flex items-start gap-2.5">
              <AlertCircle className="size-4 shrink-0 mt-0.5" />
              <div className="space-y-1">
                <p className="font-semibold">{generalError}</p>
                <p className="text-[11px] opacity-85 leading-relaxed">
                  Check that your API credentials are valid, unexpired, have the required scopes, and that network traffic to the service is permitted.
                </p>
              </div>
            </div>
          )}

          {/* Form Fields */}
          <div className="space-y-4 py-1">
            {loadingProvider && !selectedProvider ? (
              <div className="flex items-center justify-center py-8 text-sm text-muted-foreground">
                <Loader2 className="mr-2 h-4 w-4 animate-spin text-primary" />
                Loading provider configuration…
              </div>
            ) : selectedProvider ? (
              <>
                <div className="space-y-1.5">
                  <div className="flex items-center justify-between">
                    <Label
                      htmlFor="provider-instance-name"
                      className="text-xs font-medium text-foreground dark:text-white/85"
                    >
                      Instance / Connection Name
                    </Label>
                    <span className="text-[10px] text-muted-foreground dark:text-white/35 font-mono">
                      Optional
                    </span>
                  </div>
                  <Input
                    id="provider-instance-name"
                    type="text"
                    value={instanceName}
                    onChange={(e) => setInstanceName(e.target.value)}
                    placeholder={`e.g. Production ${selectedProvider.name}`}
                    className="bg-background dark:bg-[#0a0a0c] border-input dark:border-white/[0.08] text-sm h-10"
                    autoComplete="off"
                  />
                  <p className="text-[11px] text-muted-foreground dark:text-white/45">
                    Assign a distinct name to differentiate multiple environments (e.g. Prod, Staging).
                  </p>
                </div>
                {selectedProvider.credentialFields.map((field) => {
                const isSecret = field.secret;
                const isVisible = visibleSecrets[field.key] ?? false;

                return (
                  <div key={field.key} className="space-y-1.5">
                    <div className="flex items-center justify-between">
                      <Label
                        htmlFor={`provider-${field.key}`}
                        className="text-xs font-medium text-foreground dark:text-white/85"
                      >
                        {field.label}
                      </Label>
                      {field.example && (
                        <span className="text-[10px] text-muted-foreground dark:text-white/35 font-mono">
                          e.g. {field.example}
                        </span>
                      )}
                    </div>

                    <div className="relative">
                      <Input
                        id={`provider-${field.key}`}
                        type={isSecret && !isVisible ? "password" : "text"}
                        value={providerConfig[field.key] ?? ""}
                        onChange={(e) => updateProviderField(field.key, e.target.value)}
                        placeholder={field.placeholder || `Enter ${field.label}...`}
                        className={`bg-background dark:bg-[#0a0a0c] border-input dark:border-white/[0.08] text-sm h-10 ${
                          isSecret ? "pr-10" : ""
                        }`}
                        autoComplete="off"
                        aria-invalid={!!errors[field.key]}
                      />

                      {isSecret && (
                        <button
                          type="button"
                          onClick={() => toggleSecretVisibility(field.key)}
                          className="absolute right-2.5 top-1/2 -translate-y-1/2 text-muted-foreground dark:text-white/40 hover:text-foreground dark:hover:text-white p-1 rounded transition-colors"
                          title={isVisible ? "Hide value" : "Show value"}
                          aria-label={isVisible ? "Hide value" : "Show value"}
                        >
                          {isVisible ? (
                            <EyeOff className="size-4" />
                          ) : (
                            <Eye className="size-4" />
                          )}
                        </button>
                      )}
                    </div>

                    {errors[field.key] ? (
                      <p className="text-xs text-destructive dark:text-red-400 font-medium">
                        {errors[field.key]}
                      </p>
                    ) : field.help ? (
                      <p className="text-xs text-muted-foreground dark:text-white/45 leading-relaxed">
                        {field.help}
                      </p>
                    ) : null}
                  </div>
                );
              })}
            </>
          ) : null}
          </div>

          <DialogFooter className="pt-2 border-t border-border dark:border-white/[0.06] flex items-center justify-end gap-2">
            <Button
              type="button"
              variant="ghost"
              onClick={() => handleOpenChange(false)}
              className="text-muted-foreground hover:text-foreground dark:text-white/60 dark:hover:text-white h-9 text-xs"
              disabled={isSubmitting}
            >
              Cancel
            </Button>

            <Button
              type="submit"
              className="bg-primary text-primary-foreground hover:bg-primary/90 h-9 px-4 text-xs font-medium shadow-sm"
              disabled={isSubmitting || loadingProvider || !selectedProvider}
            >
              {isSubmitting && <Loader2 className="mr-2 h-3.5 w-3.5 animate-spin" />}
              Connect
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}