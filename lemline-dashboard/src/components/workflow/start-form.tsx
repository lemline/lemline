import { useEffect, useMemo, useRef, useState } from "react";
import { uuidv7 } from "uuidv7";
import { toast } from "sonner";
import { useNavigate } from "react-router-dom";
import { StartWorkflowRequest } from "../../gen/proto/lemline/gateway/v1/workflow_gateway_pb";
import { gatewayClient } from "../../lib/grpc-client";
import { useNamespaces } from "../../hooks/use-namespaces";
import { useDefinitions } from "../../hooks/use-definitions";
import { Input } from "../ui/input";
import { Select } from "../ui/select";
import { Button } from "../ui/button";
import { Card } from "../ui/card";

interface StartFormProps {
  initialNamespace?: string;
  initialName?: string;
  initialVersion?: string;
}

export function StartForm({ initialNamespace, initialName, initialVersion }: StartFormProps) {
  const navigate = useNavigate();
  const namespacesQuery = useNamespaces();
  const namespaces = namespacesQuery.data ?? [];
  const [namespace, setNamespace] = useState(initialNamespace ?? "");
  const [name, setName] = useState(initialName ?? "");
  const [version, setVersion] = useState(initialVersion ?? "");
  const [workflowId, setWorkflowId] = useState(uuidv7());
  const [zoneId, setZoneId] = useState("UTC");
  const [inputJson, setInputJson] = useState("{}");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const redirectTimerRef = useRef<number | null>(null);

  const { data: definitionNames = [] } = useDefinitions({
    namespace,
    latestOnly: true,
    enabled: Boolean(namespace),
  });

  const { data: definitionVersions = [] } = useDefinitions({
    namespace,
    name,
    latestOnly: false,
    enabled: Boolean(namespace && name),
  });

  const nameOptions = useMemo(
    () => Array.from(new Set(definitionNames.map((item) => item.name))).map((item) => ({ value: item, label: item })),
    [definitionNames],
  );

  const versionOptions = useMemo(
    () => {
      const unique = Array.from(new Set(definitionVersions.map((item) => item.version)));
      unique.sort((left, right) =>
        right.localeCompare(left, undefined, {
          numeric: true,
          sensitivity: "base",
        }),
      );
      return unique.map((item) => ({
        value: item,
        label: item,
      }));
    },
    [definitionVersions],
  );

  useEffect(() => {
    if (version || versionOptions.length === 0) return;
    setVersion(versionOptions[0].value);
  }, [version, versionOptions]);

  useEffect(() => {
    return () => {
      if (redirectTimerRef.current != null) {
        window.clearTimeout(redirectTimerRef.current);
      }
    };
  }, []);

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    setLoading(true);

    try {
      JSON.parse(inputJson);
      const workflowIdToStart = workflowId.trim() || uuidv7();
      if (workflowIdToStart !== workflowId) {
        setWorkflowId(workflowIdToStart);
      }

      const response = await gatewayClient.startWorkflow(
        new StartWorkflowRequest({
          workflowId: workflowIdToStart,
          namespace,
          name,
          version,
          inputJson,
          zoneId,
        }),
      );

      const instancePath = `/id/${response.workflowId}`;
      toast.success("Workflow started", {
        description: "Redirecting to instance details...",
        duration: 5000,
        action: {
          label: "Open instance",
          onClick: () => navigate(instancePath),
        },
      });

      if (redirectTimerRef.current != null) {
        window.clearTimeout(redirectTimerRef.current);
      }
      redirectTimerRef.current = window.setTimeout(() => {
        redirectTimerRef.current = null;
        navigate(instancePath);
      }, 2000);
    } catch (caught) {
      const message = caught instanceof Error ? caught.message : "Failed to start workflow";
      setError(message);
      toast.error("Failed to start workflow", { description: message });
    } finally {
      setLoading(false);
    }
  };

  return (
    <Card>
      <form className="space-y-4" onSubmit={onSubmit}>
        <div className="grid gap-4 md:grid-cols-3">
          <div>
            <label className="mb-1 block text-sm font-medium">Namespace</label>
            <Select
              value={namespace}
              onChange={(event) => {
                setNamespace(event.target.value);
                setName("");
                setVersion("");
              }}
              options={namespaces.map((item) => ({ value: item, label: item }))}
              disabled={namespacesQuery.isLoading || namespacesQuery.isError}
            />
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium">Workflow name</label>
            <Select
              value={name}
              onChange={(event) => {
                setName(event.target.value);
                setVersion("");
              }}
              options={nameOptions}
              disabled={!namespace}
            />
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium">Version</label>
            <Select
              value={version}
              onChange={(event) => setVersion(event.target.value)}
              options={versionOptions}
              disabled={!name}
            />
          </div>
        </div>

        <div className="grid gap-4 md:grid-cols-2">
          <div>
            <label className="mb-1 block text-sm font-medium">Workflow ID (UUIDv7)</label>
            <Input value={workflowId} onChange={(event) => setWorkflowId(event.target.value)} />
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium">Zone ID</label>
            <Input value={zoneId} onChange={(event) => setZoneId(event.target.value)} />
          </div>
        </div>

        <div>
          <label className="mb-1 block text-sm font-medium">Input JSON</label>
          <textarea
            className="min-h-40 w-full rounded-md border border-slate-300 bg-slate-50 p-3 font-mono text-sm outline-none focus:border-slate-500 dark:border-slate-700 dark:bg-slate-900"
            value={inputJson}
            onChange={(event) => setInputJson(event.target.value)}
          />
        </div>

        {error && <p className="text-sm text-red-600 dark:text-red-300">{error}</p>}
        {namespacesQuery.isLoading && (
          <p className="text-sm text-slate-500 dark:text-slate-400">Loading namespaces...</p>
        )}
        {namespacesQuery.isError && (
          <p className="text-sm text-red-600 dark:text-red-300">
            Unable to load namespaces: {toErrorMessage(namespacesQuery.error)}
          </p>
        )}

        <Button type="submit" disabled={loading || !namespace || !name || !version}>
          {loading ? "Starting..." : "Start workflow"}
        </Button>
      </form>
    </Card>
  );
}

function toErrorMessage(error: unknown): string {
  return error instanceof Error ? error.message : "Unknown error";
}
