"use client";

import { useEffect, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2 } from "lucide-react";
import { toast } from "sonner";
import { ProviderIcon } from "@/components/integrations/provider-icon";

import {
  connectIntegration,
  connectProvider,
  fetchProviders,
  getErrorMessage,
  getFieldErrors,
} from "@/lib/api";
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

const SLACK_WEBHOOK_DOCS = "https://api.slack.com/messaging/webhooks";

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

  const [webhookUrl, setWebhookUrl] = useState("");
  const [selectedProvider, setSelectedProvider] =
    useState<ProviderInfo | null>(null);
  const [providerConfig, setProviderConfig] = useState<
    Record<string, string>
  >({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [loadingProvider, setLoadingProvider] = useState(false);

  const isSlack = providerType === "slack";

  /*
   * When the dialog opens for n8n/Make/Zapier/etc.,
   * load the exact provider selected from the integrations page.
   */
  useEffect(() => {
    if (!open || !providerType || isSlack) {
      setSelectedProvider(null);
      setProviderConfig({});
      return;
    }

    let cancelled = false;

    async function loadProvider() {
      setLoadingProvider(true);
      setErrors({});

      try {
        const response = await fetchProviders();

        if (cancelled) return;

        const provider = response.providers.find(
          (item) => item.type === providerType,
        );

        if (!provider) {
          setSelectedProvider(null);
          setErrors({
            provider: `Provider "${providerType}" is not available.`,
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
          setErrors({
            provider: getErrorMessage(
              err,
              "Could not load provider configuration.",
            ),
          });
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
  }, [open, providerType, isSlack]);

  /*
   * Slack keeps its existing dedicated integration API.
   */
  const slackMutation = useMutation({
    mutationFn: () =>
      connectIntegration({
        type: "slack",
        webhookUrl: webhookUrl.trim(),
      }),

    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ["integrations"],
      });

      toast.success("Slack connected.");

      setWebhookUrl("");
      setErrors({});

      onOpenChange(false);
    },

    onError: (err) => {
      const fieldErrors = getFieldErrors(err);

      if (Object.keys(fieldErrors).length > 0) {
        setErrors(fieldErrors);
      } else {
        toast.error(
          getErrorMessage(err, "Could not connect Slack."),
        );
      }
    },
  });

  /*
   * n8n and other workflow providers use the provider-agnostic API.
   */
  const providerMutation = useMutation({
    mutationFn: () => {
      if (!selectedProvider) {
        throw new Error("Provider configuration is unavailable.");
      }

      return connectProvider({
        type: selectedProvider.type,
        name: selectedProvider.name,
        config: providerConfig,
      });
    },

    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ["integrations"],
      });

      toast.success(
        `${selectedProvider?.name ?? "Provider"} connected.`,
      );

      setProviderConfig({});
      setSelectedProvider(null);
      setErrors({});

      onOpenChange(false);
    },

    onError: (err) => {
      const fieldErrors = getFieldErrors(err);

      if (Object.keys(fieldErrors).length > 0) {
        setErrors(fieldErrors);
      } else {
        toast.error(
          getErrorMessage(
            err,
            `Could not connect ${
              selectedProvider?.name ?? "provider"
            }.`,
          ),
        );
      }
    },
  });

  function updateProviderField(
    key: string,
    value: string,
  ) {
    setProviderConfig((current) => ({
      ...current,
      [key]: value,
    }));

    setErrors((current) => {
      if (!current[key]) {
        return current;
      }

      const next = { ...current };
      delete next[key];

      return next;
    });
  }

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setErrors({});

    /*
     * Slack flow.
     */
    if (isSlack) {
      if (!webhookUrl.trim()) {
        setErrors({
          webhookUrl:
            "Paste your Slack incoming-webhook URL.",
        });
        return;
      }

      slackMutation.mutate();
      return;
    }

    /*
     * Workflow provider flow.
     */
    if (!selectedProvider) {
      setErrors({
        provider:
          "Provider configuration is not available.",
      });
      return;
    }

    /*
     * CredentialField does not contain a `required` property.
     * The backend remains the source of truth for validation.
     *
     * We only prevent submitting a completely empty form.
     */
    const hasValue = selectedProvider.credentialFields.some(
      (field) =>
        providerConfig[field.key]?.trim().length > 0,
    );

    if (!hasValue) {
      setErrors({
        provider:
          "Enter the required provider credentials.",
      });
      return;
    }

    providerMutation.mutate();
  }

  function handleOpenChange(nextOpen: boolean) {
    if (!nextOpen) {
      setWebhookUrl("");
      setProviderConfig({});
      setSelectedProvider(null);
      setErrors({});
    }

    onOpenChange(nextOpen);
  }

  const isSubmitting =
    slackMutation.isPending ||
    providerMutation.isPending;

  const title = isSlack
    ? "Connect Slack"
    : selectedProvider
      ? `Connect ${selectedProvider.name}`
      : providerType
        ? `Connect ${providerType}`
        : "Connect integration";

  const description = isSlack
    ? "Paste an incoming-webhook URL. Notification nodes set to the Slack channel will post to it during a run."
    : selectedProvider?.description ??
      "Enter the credentials required to connect this workflow provider.";

  return (
    <Dialog
      open={open}
      onOpenChange={handleOpenChange}
    >
      <DialogContent className="border-white/[0.08] !bg-[#0a0a0a] max-w-md">
        <form onSubmit={onSubmit}>
          <DialogHeader className="space-y-3">
            <div className="flex items-center gap-3">
              {selectedProvider && (
                <div className="flex size-10 items-center justify-center rounded-lg border border-white/[0.08] bg-white/[0.04]">
                  <ProviderIcon
                    provider={selectedProvider.type}
                    name={selectedProvider.name}
                    icon={selectedProvider.icon}
                    className="size-5"
                    theme="dark"
                    fallbackInitials
                  />
                </div>
              )}
              <div className="space-y-0.5">
                <DialogTitle className="text-white/90">{title}</DialogTitle>
                <DialogDescription className="text-white/44">
                  {description}
                </DialogDescription>
              </div>
            </div>
          </DialogHeader>

          <div className="space-y-4 py-6">
            {/*
             * =========================
             * SLACK
             * =========================
             */}
            {isSlack ? (
              <div className="space-y-2">
                <Label htmlFor="slack-webhook" className="text-white/90">
                  Incoming webhook URL
                </Label>

                <Input
                  id="slack-webhook"
                  autoFocus
                  type="url"
                  value={webhookUrl}
                  onChange={(e) =>
                    setWebhookUrl(e.target.value)
                  }
                  placeholder="https://hooks.slack.com/services/..."
                  className="bg-[#0a0a0a] border-white/[0.08]"
                  aria-invalid={!!errors.webhookUrl}
                />

                {errors.webhookUrl ? (
                  <p className="text-xs text-destructive">
                    {errors.webhookUrl}
                  </p>
                ) : (
                  <p className="text-xs text-white/44">
                    Create one in Slack under{" "}
                    <a
                      href={SLACK_WEBHOOK_DOCS}
                      target="_blank"
                      rel="noreferrer"
                      className="underline underline-offset-2 hover:text-white/90"
                    >
                      Incoming Webhooks
                    </a>
                    .
                  </p>
                )}
              </div>
            ) : loadingProvider ? (
              /*
               * =========================
               * LOADING PROVIDER
               * =========================
               */
              <div className="flex items-center justify-center py-8 text-sm text-muted-foreground">
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                Loading provider configuration…
              </div>
            ) : errors.provider ? (
              <p className="text-sm text-destructive">
                {errors.provider}
              </p>
            ) : selectedProvider ? (
              /*
               * =========================
               * WORKFLOW PROVIDER
               * =========================
               */
              <div className="space-y-4">
                {selectedProvider.credentialFields.map(
                  (field) => (
                    <div
                      key={field.key}
                      className="space-y-2"
                    >
                      <Label
                        htmlFor={`provider-${field.key}`}
                        className="text-white/90"
                      >
                        {field.label}
                      </Label>

                      <Input
                        id={`provider-${field.key}`}
                        type={
                          field.secret
                            ? "password"
                            : "text"
                        }
                        value={
                          providerConfig[field.key] ?? ""
                        }
                        onChange={(e) =>
                          updateProviderField(
                            field.key,
                            e.target.value,
                          )
                        }
                        placeholder={
                          field.placeholder
                        }
                        className="bg-[#0a0a0a] border-white/[0.08]"
                        autoComplete="off"
                        aria-invalid={
                          !!errors[field.key]
                        }
                      />

                      {errors[field.key] ? (
                        <p className="text-xs text-destructive">
                          {errors[field.key]}
                        </p>
                      ) : field.help ? (
                        <p className="text-xs text-white/44">
                          {field.help}
                        </p>
                      ) : null}
                    </div>
                  ),
                )}
              </div>
            ) : null}
          </div>

          <DialogFooter>
            <Button
              type="button"
              variant="ghost"
              onClick={() =>
                handleOpenChange(false)
              }
              className="text-white/60 hover:text-white"
              disabled={isSubmitting}
            >
              Cancel
            </Button>

            <Button
              type="submit"
              className="bg-primary text-primary-foreground hover:bg-primary/90"
              disabled={
                isSubmitting ||
                loadingProvider ||
                (!isSlack &&
                  !selectedProvider)
              }
            >
              {isSubmitting && (
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              )}

              Connect
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}