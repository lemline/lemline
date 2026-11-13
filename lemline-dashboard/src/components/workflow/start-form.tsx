import { create } from "@bufbuild/protobuf";
import { useEffect, useMemo, useRef, useState } from "react";
import { uuidv7 } from "uuidv7";
import { toast } from "sonner";
import { Link, useNavigate } from "react-router-dom";
import { StartInstanceRequestSchema } from "../../gen/proto/lemline/gateway/v1/gateway_pb";
import { gatewayClient } from "../../lib/grpc-client";
import { useNamespaces } from "../../hooks/use-namespaces";
import { useDefinitions } from "../../hooks/use-definitions";
import { timestampToMilliseconds, valueFromJsonText } from "../../lib/proto";
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
  const [selectedVersion, setVersion] = useState(initialVersion ?? "");
  const [workflowId, setWorkflowId] = useState(uuidv7());
  const [zoneId, setZoneId] = useState("UTC");
  const [inputJson, setInputJson] = useState("{}");
  const [error, setError] = useState<string | null>(null);
  const [scheduleConfirmation, setScheduleConfirmation] = useState<{ path: string; nextRunAt?: number } | null>(null);
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

  const version = selectedVersion || versionOptions[0]?.value || "";

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
    setScheduleConfirmation(null);
    if (redirectTimerRef.current != null) {
      window.clearTimeout(redirectTimerRef.current);
      redirectTimerRef.current = null;
    }
    setLoading(true);

    try {
      const input = valueFromJsonText(inputJson);
      const workflowIdToStart = workflowId.trim() || uuidv7();
      if (workflowIdToStart !== workflowId) {
        setWorkflowId(workflowIdToStart);
      }

      const response = await gatewayClient.startInstance(
        create(StartInstanceRequestSchema, {
          instanceId: workflowIdToStart,
          namespace,
          name,
          version,
          input,
          zoneId,
        }),
      );

      if (response.scheduleOnly) {
        const path = `/ns/${encodeURIComponent(response.namespace)}/name/${encodeURIComponent(response.name)}`;
        const scheduledAt = timestampToMilliseconds(response.nextRunAt);
        const nextRunAt = scheduledAt != null && scheduledAt > Date.now() ? scheduledAt : undefined;
        setScheduleConfirmation({ path, nextRunAt });
        toast.success("Workflow scheduled", {
          description: nextRunAt
            ? `Next run at ${new Date(nextRunAt).toLocaleString()}`
            : "The workflow will run on its schedule.",
          action: { label: "View workflow", onClick: () => navigate(path) },
        });
        return;
      }

      const instancePath = `/id/${response.instanceId}`;
      toast.success("Workflow started", {
        description: "Redirecting to instance details...",
        duration: 5000,
        action: {
          label: "Open instance",
          onClick: () => navigate(instancePath),
        },
      });

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
                setScheduleConfirmation(null);
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
                setScheduleConfirmation(null);
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
              onChange={(event) => {
                setScheduleConfirmation(null);
                setVersion(event.target.value);
              }}
              options={versionOptions}
              disabled={!name}
            />
          </div>
        </div>

        <div className="grid gap-4 md:grid-cols-2">
          <div>
            <label className="mb-1 block text-sm font-medium">Workflow ID (UUIDv7)</label>
            <Input value={workflowId} onChange={(event) => {
              setScheduleConfirmation(null);
              setWorkflowId(event.target.value);
            }} />
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium">Zone ID</label>
            <Input value={zoneId} onChange={(event) => {
              setScheduleConfirmation(null);
              setZoneId(event.target.value);
            }} />
          </div>
        </div>

        <div>
          <label className="mb-1 block text-sm font-medium">Input JSON</label>
          <textarea
            className="min-h-40 w-full rounded-md border border-slate-300 bg-slate-50 p-3 font-mono text-sm outline-hidden focus:border-slate-500 dark:border-slate-700 dark:bg-slate-900"
            value={inputJson}
            onChange={(event) => {
              setScheduleConfirmation(null);
              setInputJson(event.target.value);
            }}
          />
        </div>

        {error && <p className="text-sm text-red-600 dark:text-red-300">{error}</p>}
        {scheduleConfirmation && (
          <div role="status" className="rounded-md border border-emerald-300 bg-emerald-50 p-3 text-sm text-emerald-950 dark:border-emerald-800 dark:bg-emerald-950 dark:text-emerald-100">
            <p>Workflow scheduled{scheduleConfirmation.nextRunAt != null
              ? ` — next run at ${new Date(scheduleConfirmation.nextRunAt).toLocaleString()}`
              : ""}.</p>
            <Link className="font-medium underline" to={scheduleConfirmation.path}>View workflow instances</Link>
          </div>
        )}
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
