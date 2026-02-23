import { useEffect, useMemo, useState } from "react";
import { Plus } from "lucide-react";
import { useLocation, useNavigate, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useNamespaces } from "../../hooks/use-namespaces";
import { useDefinitions } from "../../hooks/use-definitions";
import { useInstanceTimeline } from "../../hooks/use-instance-timeline";
import { gatewayClient } from "../../lib/grpc-client";
import { ListInstancesRequest } from "../../gen/proto/lemline/gateway/v1/workflow_gateway_pb";
import { shortId } from "../../lib/utils";
import { Input } from "../ui/input";
import { Select } from "../ui/select";
import { Button } from "../ui/button";
import { StatusBadge } from "../instances/status-badge";

export function CascadingSelects() {
  const params = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const { data: namespaces = [] } = useNamespaces();

  const namespace = params.namespace;
  const name = params.name;
  const version = params.version;
  const workflowIdParam = params.workflowId;
  const isInstanceRoute = location.pathname.startsWith("/id/");
  const [workflowIdQuery, setWorkflowIdQuery] = useState("");
  const [workflowInputFocused, setWorkflowInputFocused] = useState(false);
  const [highlightedSuggestion, setHighlightedSuggestion] = useState(-1);
  const debouncedWorkflowIdQuery = useDebouncedValue(workflowIdQuery.trim(), 300);

  const instanceTimeline = useInstanceTimeline(workflowIdParam, true);

  const resolvedNamespace = isInstanceRoute ? (instanceTimeline.data?.namespace ?? namespace) : namespace;
  const resolvedName = isInstanceRoute ? (instanceTimeline.data?.name ?? name) : name;
  const resolvedVersion = isInstanceRoute ? (instanceTimeline.data?.version ?? version) : version;

  const { data: names = [] } = useDefinitions({
    namespace: resolvedNamespace,
    latestOnly: true,
    enabled: Boolean(resolvedNamespace),
  });

  const { data: versions = [] } = useDefinitions({
    namespace: resolvedNamespace,
    name: resolvedName,
    latestOnly: false,
    enabled: Boolean(resolvedNamespace && resolvedName),
  });

  const workflowSuggestionsQuery = useQuery({
    queryKey: ["workflow-id-suggestions", debouncedWorkflowIdQuery],
    enabled: workflowInputFocused && debouncedWorkflowIdQuery.length >= 4,
    queryFn: async () => {
      const response = await gatewayClient.listInstances(
        new ListInstancesRequest({
          workflowIdPrefix: debouncedWorkflowIdQuery,
          pageSize: 10,
        }),
      );
      return response.instances;
    },
  });

  const workflowSuggestions = workflowSuggestionsQuery.data ?? [];
  const showSuggestions =
    workflowInputFocused &&
    workflowIdQuery.trim().length >= 4 &&
    (workflowSuggestionsQuery.isLoading || workflowSuggestions.length > 0 || workflowSuggestionsQuery.isFetched);

  const namespaceOptions = useMemo(
    () => [
      { value: "all", label: "All namespaces" },
      ...namespaces.map((item) => ({ value: item, label: item })),
    ],
    [namespaces],
  );

  const nameOptions = useMemo(() => {
    const unique = Array.from(new Set(names.map((item) => item.name)));
    return [{ value: "all", label: "All names" }, ...unique.map((item) => ({ value: item, label: item }))];
  }, [names]);

  const versionOptions = useMemo(() => {
    const unique = Array.from(new Set(versions.map((item) => item.version)));
    return [{ value: "all", label: "All versions" }, ...unique.map((item) => ({ value: item, label: item }))];
  }, [versions]);

  const onNamespaceChange = (value: string) => {
    if (value === "all") {
      navigate("/");
      return;
    }
    navigate(`/ns/${encodeURIComponent(value)}`);
  };

  const onNameChange = (value: string) => {
    if (!resolvedNamespace) return;
    if (value === "all") {
      navigate(`/ns/${encodeURIComponent(resolvedNamespace)}`);
      return;
    }
    navigate(`/ns/${encodeURIComponent(resolvedNamespace)}/name/${encodeURIComponent(value)}`);
  };

  const onVersionChange = (value: string) => {
    if (!resolvedNamespace || !resolvedName) return;
    if (value === "all") {
      navigate(`/ns/${encodeURIComponent(resolvedNamespace)}/name/${encodeURIComponent(resolvedName)}`);
      return;
    }
    navigate(
      `/ns/${encodeURIComponent(resolvedNamespace)}/name/${encodeURIComponent(resolvedName)}/v/${encodeURIComponent(value)}`,
    );
  };

  const navigateToWorkflowId = (value: string) => {
    const trimmed = value.trim();
    if (!trimmed) return;
    setWorkflowInputFocused(false);
    setHighlightedSuggestion(-1);
    setWorkflowIdQuery(trimmed);
    navigate(`/id/${trimmed}`);
  };

  const onWorkflowIdKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    const total = workflowSuggestions.length;

    if (event.key === "ArrowDown") {
      if (total === 0) return;
      event.preventDefault();
      setHighlightedSuggestion((current) => (current + 1) % total);
      return;
    }

    if (event.key === "ArrowUp") {
      if (total === 0) return;
      event.preventDefault();
      setHighlightedSuggestion((current) => (current <= 0 ? total - 1 : current - 1));
      return;
    }

    if (event.key === "Escape") {
      setWorkflowInputFocused(false);
      setHighlightedSuggestion(-1);
      return;
    }

    if (event.key !== "Enter") return;
    event.preventDefault();
    if (highlightedSuggestion >= 0 && highlightedSuggestion < workflowSuggestions.length) {
      navigateToWorkflowId(workflowSuggestions[highlightedSuggestion].workflowId);
      return;
    }

    navigateToWorkflowId(event.currentTarget.value);
  };

  const openStartForm = () => {
    const query = new URLSearchParams();
    if (resolvedNamespace) query.set("namespace", resolvedNamespace);
    if (resolvedName) query.set("name", resolvedName);
    if (resolvedVersion) query.set("version", resolvedVersion);
    navigate(`/start${query.toString() ? `?${query}` : ""}`);
  };

  const disableName = !resolvedNamespace;
  const disableVersion = !resolvedNamespace || !resolvedName;

  return (
    <div className="space-y-3 rounded-xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-950">
      <div className="grid gap-3 lg:grid-cols-[1.4fr_1fr_1fr_1fr_auto]">
        <div className="relative">
          <Input
            key={workflowIdParam ?? "workflow-id-input"}
            defaultValue={workflowIdParam ?? ""}
            onChange={(event) => {
              setWorkflowIdQuery(event.currentTarget.value);
              setHighlightedSuggestion(-1);
            }}
            onFocus={(event) => {
              setWorkflowInputFocused(true);
              setWorkflowIdQuery(event.currentTarget.value);
            }}
            onBlur={() => {
              window.setTimeout(() => {
                setWorkflowInputFocused(false);
                setHighlightedSuggestion(-1);
              }, 120);
            }}
            onKeyDown={onWorkflowIdKeyDown}
            placeholder="Workflow ID..."
          />
          {showSuggestions && (
            <div className="absolute z-20 mt-1 max-h-64 w-full overflow-auto rounded-md border border-slate-200 bg-white shadow-lg dark:border-slate-700 dark:bg-slate-900">
              {workflowSuggestionsQuery.isLoading && (
                <p className="px-3 py-2 text-xs text-slate-500">Searching...</p>
              )}
              {!workflowSuggestionsQuery.isLoading && workflowSuggestions.length === 0 && (
                <p className="px-3 py-2 text-xs text-slate-500">No matching workflow IDs.</p>
              )}
              {workflowSuggestions.map((instance, index) => (
                <button
                  key={instance.workflowId}
                  type="button"
                  onMouseDown={(event) => event.preventDefault()}
                  onMouseEnter={() => setHighlightedSuggestion(index)}
                  onClick={() => navigateToWorkflowId(instance.workflowId)}
                  className={`flex w-full items-center justify-between gap-3 px-3 py-2 text-left text-sm ${
                    index === highlightedSuggestion
                      ? "bg-slate-100 dark:bg-slate-800"
                      : "hover:bg-slate-50 dark:hover:bg-slate-800/60"
                  }`}
                >
                  <div className="min-w-0">
                    <p className="font-medium text-slate-800 dark:text-slate-100">{shortId(instance.workflowId, 10, 6)}</p>
                    <p className="truncate text-xs text-slate-500">
                      {instance.name} · v{instance.version}
                    </p>
                  </div>
                  <StatusBadge status={instance.status} />
                </button>
              ))}
            </div>
          )}
        </div>
        <Select
          value={resolvedNamespace ?? "all"}
          options={namespaceOptions}
          onChange={(event) => onNamespaceChange(event.target.value)}
        />
        <Select
          value={resolvedName ?? "all"}
          options={nameOptions}
          onChange={(event) => onNameChange(event.target.value)}
          disabled={disableName}
          className={disableName ? "cursor-not-allowed opacity-50" : undefined}
        />
        <Select
          value={resolvedVersion ?? "all"}
          options={versionOptions}
          onChange={(event) => onVersionChange(event.target.value)}
          disabled={disableVersion}
          className={disableVersion ? "cursor-not-allowed opacity-50" : undefined}
        />
        <Button onClick={openStartForm} className="gap-2">
          <Plus className="h-4 w-4" /> Start Workflow
        </Button>
      </div>
      <p className="text-xs text-slate-500 dark:text-slate-400">
        {location.pathname.startsWith("/id/")
          ? "Viewing a specific instance. Use Workflow ID or selectors to navigate to related workflows."
          : "Use selectors to drill down by namespace, workflow name, and version."}
      </p>
    </div>
  );
}

function useDebouncedValue(value: string, delayMs: number): string {
  const [debouncedValue, setDebouncedValue] = useState(value);

  useEffect(() => {
    const timeoutId = window.setTimeout(() => {
      setDebouncedValue(value);
    }, delayMs);
    return () => window.clearTimeout(timeoutId);
  }, [delayMs, value]);

  return debouncedValue;
}
