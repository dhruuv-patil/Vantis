"use client";

import { useState, useEffect, useCallback } from "react";
import { Loader2, RefreshCw, Eye, EyeOff, Zap, Search } from "lucide-react";
import { toast } from "sonner";

import {
  testIntegration,
  discoverIntegrationWorkflows,
  monitorProviderWorkflow,
} from "@/lib/api";
import { getErrorMessage } from "@/lib/api";
import { ProviderIcon } from "@/components/integrations/provider-icon";
import type { ExternalWorkflow, MonitoredWorkflowsResponse } from "@/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Card,
} from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

interface WorkflowDiscoveryProps {
  integrationId: string;
  integrationName: string;
  onWorkflowsChange?: (workflows: ExternalWorkflow[]) => void;
  onClose: () => void;
}

export function WorkflowDiscovery({
  integrationId,
  integrationName,
  onWorkflowsChange,
  onClose,
}: WorkflowDiscoveryProps) {
  const [workflows, setWorkflows] = useState<ExternalWorkflow[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isTesting, setIsTesting] = useState(false);
  const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null);
  const [searchQuery, setSearchQuery] = useState("");

  const loadWorkflows = useCallback(async () => {
    setIsLoading(true);
    try {
      const resp = await discoverIntegrationWorkflows(integrationId);
      setWorkflows(resp.workflows);
      onWorkflowsChange?.(resp.workflows);
    } catch (err) {
      toast.error(getErrorMessage(err, "Could not load workflows."));
    } finally {
      setIsLoading(false);
    }
  }, [integrationId, onWorkflowsChange]);

  useEffect(() => {
    loadWorkflows();
  }, [loadWorkflows]);

  const handleTestConnection = async () => {
    setIsTesting(true);
    setTestResult(null);
    try {
      const result = await testIntegration(integrationId);
      setTestResult(result);
      if (result.success) {
        toast.success("Connection test passed.");
      } else {
        toast.error(`Test failed: ${result.message ?? "Unknown error"}`);
      }
    } catch (err) {
      const msg = getErrorMessage(err, "Test failed");
      setTestResult({ success: false, message: msg });
      toast.error(msg);
    } finally {
      setIsTesting(false);
    }
  };

  const handleToggleMonitor = async (workflow: ExternalWorkflow, monitoring: boolean) => {
    try {
      await monitorProviderWorkflow(integrationId, workflow.id, monitoring);
      setWorkflows((prev) =>
        prev.map((w) =>
          w.id === workflow.id ? { ...w, monitoring } : w
        ),
      );
      toast.success(monitoring ? "Monitoring started." : "Monitoring stopped.");
    } catch (err) {
      toast.error(getErrorMessage(err, monitoring ? "Could not start monitoring." : "Could not stop monitoring."));
    }
  };

  const filteredWorkflows = workflows.filter(
    (w) =>
      w.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      w.id.toLowerCase().includes(searchQuery.toLowerCase()),
  );

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="border-border dark:border-white/[0.08] bg-card dark:bg-[#0a0a0a] max-w-4xl max-h-[80vh]">
        <DialogHeader className="space-y-3">
          <div className="flex items-center gap-3">
            <div className="flex size-10 shrink-0 items-center justify-center rounded-lg border border-border dark:border-white/[0.08] bg-muted dark:bg-white/[0.04]">
              <ProviderIcon name={integrationName} className="size-5" theme="auto" fallbackInitials />
            </div>
            <div className="space-y-0.5">
              <DialogTitle className="text-foreground dark:text-white/90">Workflows for {integrationName}</DialogTitle>
              <DialogDescription className="text-muted-foreground dark:text-white/44">
                Discovered workflows from your {integrationName} instance. Enable monitoring
                to sync execution telemetry and run anomaly detection.
              </DialogDescription>
            </div>
          </div>
        </DialogHeader>

        <div className="space-y-4 py-4">
          {/* Test connection bar */}
          <div className="flex items-center gap-3">
            <Button variant="outline" onClick={handleTestConnection} disabled={isTesting}>
              {isTesting && <Loader2 className="size-4 animate-spin mr-2" />}
              Test Connection
            </Button>
            {testResult && (
              <Badge variant={testResult.success ? "success" : "destructive"}>
                {testResult.success ? "Connected" : "Failed"}
              </Badge>
            )}
            <Button variant="ghost" size="icon" onClick={loadWorkflows} className="text-muted-foreground hover:text-foreground dark:text-white/60 dark:hover:text-white">
              <RefreshCw className="size-4" />
            </Button>
          </div>

          {/* Search */}
          <div className="relative">
            <Input
              placeholder="Search workflows…"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="pl-9"
            />
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 size-4 text-muted-foreground dark:text-white/44" />
          </div>

          {isLoading ? (
            <div className="flex items-center justify-center py-8">
              <Loader2 className="size-6 animate-spin text-primary" />
            </div>
          ) : filteredWorkflows.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-12 text-muted-foreground dark:text-white/44">
              <p>No workflows found.</p>
              <p className="text-sm">Click &quot;Test Connection&quot; then refresh to discover workflows.</p>
            </div>
          ) : (
            <Card className="border-border dark:border-white/[0.08] bg-card dark:bg-[#0a0a0a]">
              <Table>
                <TableHeader>
                  <TableRow className="border-border dark:border-white/[0.08] hover:bg-transparent">
                    <TableHead className="text-muted-foreground dark:text-white/60">Workflow</TableHead>
                    <TableHead className="text-muted-foreground dark:text-white/60">Status</TableHead>
                    <TableHead className="text-right text-muted-foreground dark:text-white/60">Monitoring</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {filteredWorkflows.map((wf) => (
                    <TableRow key={wf.id} className="border-border dark:border-white/[0.08] hover:bg-muted/40 dark:hover:bg-white/[0.02]">
                      <TableCell>
                        <div className="flex items-center gap-3">
                          <span className="flex size-8 shrink-0 items-center justify-center rounded-lg border border-border dark:border-white/[0.08] bg-muted dark:bg-white/[0.04]">
                            <ProviderIcon name={integrationName} className="size-4" theme="auto" fallbackInitials />
                          </span>
                          <div>
                            <p className="font-medium text-foreground dark:text-white/90">{wf.name}</p>
                            <p className="text-xs text-muted-foreground dark:text-white/44 font-mono">{wf.id}</p>
                          </div>
                        </div>
                      </TableCell>
                      <TableCell>
                        <Badge variant={wf.status === "active" ? "success" : "secondary"}>
                          {wf.status}
                        </Badge>
                      </TableCell>
                      <TableCell className="text-right">
                        <Button
                          variant={wf.monitoring ? "default" : "outline"}
                          size="sm"
                          onClick={() => handleToggleMonitor(wf, !wf.monitoring)}
                          className="w-28"
                        >
                          {wf.monitoring ? (
                            <>
                              <EyeOff className="size-3 mr-1" /> Stop
                            </>
                          ) : (
                            <>
                              <Eye className="size-3 mr-1" /> Monitor
                            </>
                          )}
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </Card>
          )}
        </div>

        <DialogFooter>
          <Button variant="ghost" onClick={onClose} className="text-muted-foreground hover:text-foreground dark:text-white/60 dark:hover:text-white">
            Done
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

