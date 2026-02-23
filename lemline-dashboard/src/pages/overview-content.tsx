import { useQueryClient } from "@tanstack/react-query";
import type { InfiniteData } from "@tanstack/react-query";
import { useEffect, useMemo, useRef, useState } from "react";
import { toast } from "sonner";
import { useNavigate } from "react-router-dom";
import {
  DefinitionStats,
  ListInstancesResponse,
  WatchDefinitionStatsRequest,
  WatchInstancesRequest,
  WorkflowInstance,
} from "../gen/proto/lemline/gateway/v1/workflow_gateway_pb";
import { useDefinitionStats, definitionStatsQueryKey } from "../hooks/use-definition-stats";
import { useInstances, instancesQueryKey } from "../hooks/use-instances";
import { gatewayClient } from "../lib/grpc-client";
import { toTimeWindow } from "../lib/time-range";
import type { TimeRangePreset } from "../lib/time-range";
import { asNumber } from "../lib/utils";
import { DefinitionTable, type BreakdownRow } from "../components/definitions/definition-table";
import { StatsCards } from "../components/definitions/stats-cards";
import { TimeRangeSelector } from "../components/definitions/time-range-selector";
import { InstanceTable } from "../components/instances/instance-table";
import { NewInstancesBanner } from "../components/instances/new-instances-banner";
import { Select } from "../components/ui/select";

type OverviewLevel = "root" | "namespace" | "workflow" | "version";

interface OverviewContentProps {
  level: OverviewLevel;
  namespace?: string;
  name?: string;
  version?: string;
}

const MAX_RETRIES = 5;

export function OverviewContent({ level, namespace, name, version }: OverviewContentProps) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [timeRange, setTimeRange] = useState<TimeRangePreset>("24h");
  const [status, setStatus] = useState<string>("all");
  const [bufferedNewInstances, setBufferedNewInstances] = useState<WorkflowInstance[]>([]);

  const timeWindow = useMemo(() => toTimeWindow(timeRange), [timeRange]);
  const statsKey = useMemo(
    () =>
      definitionStatsQueryKey({
        namespace,
        name,
        version,
        timeFrom: timeWindow.from,
        timeTo: timeWindow.to,
      }),
    [name, namespace, timeWindow.from, timeWindow.to, version],
  );

  const instanceFilters = useMemo(
    () => ({
      namespace,
      name,
      version,
      status: status === "all" ? undefined : status,
      timeFrom: timeWindow.from,
      timeTo: timeWindow.to,
      pageSize: 50,
    }),
    [name, namespace, status, timeWindow.from, timeWindow.to, version],
  );

  const instancesKey = useMemo(() => instancesQueryKey(instanceFilters), [instanceFilters]);

  const { data: stats = [], isLoading: statsLoading } = useDefinitionStats({
    namespace,
    name,
    version,
    timeFrom: timeWindow.from,
    timeTo: timeWindow.to,
  });

  const instances = useInstances(instanceFilters);

  const newestSequenceRef = useRef<bigint | undefined>(undefined);
  const pendingInsertionsRef = useRef<WorkflowInstance[]>([]);
  const insertionTimerRef = useRef<number | null>(null);

  useEffect(() => {
    setBufferedNewInstances([]);
    pendingInsertionsRef.current = [];
    newestSequenceRef.current = undefined;
  }, [instancesKey]);

  useEffect(() => {
    const controller = new AbortController();
    let disposed = false;
    let retries = 0;
    let needsReconnectToast = false;

    const watch = async () => {
      while (!disposed && !controller.signal.aborted) {
        try {
          const request = new WatchDefinitionStatsRequest({
            namespace,
            name,
            version,
            timeFrom: timeWindow.from,
            timeTo: timeWindow.to,
          });

          for await (const update of gatewayClient.watchDefinitionStats(request, {
            signal: controller.signal,
          })) {
            if (needsReconnectToast) {
              toast.success("Reconnected — live updates resumed", { duration: 3000 });
              needsReconnectToast = false;
            }
            retries = 0;

            const statsUpdate = update.stats;
            if (!statsUpdate) continue;

            queryClient.setQueryData<DefinitionStats[]>(statsKey, (previous = []) => {
              const next = previous.map((row) => new DefinitionStats(row));
              const key = `${statsUpdate.namespace}/${statsUpdate.name}/${statsUpdate.version ?? "*"}`;
              const index = next.findIndex(
                (row) => `${row.namespace}/${row.name}/${row.version ?? "*"}` === key,
              );

              if (index >= 0) {
                next[index] = new DefinitionStats(statsUpdate);
              } else {
                next.push(new DefinitionStats(statsUpdate));
              }

              return next;
            });
          }

          if (controller.signal.aborted || disposed) {
            return;
          }

          throw new Error("Definition stats stream disconnected");
        } catch {
          if (controller.signal.aborted || disposed) {
            return;
          }

          retries += 1;
          needsReconnectToast = true;

          if (retries > MAX_RETRIES) {
            toast.error("Live updates unavailable. Reload page.", { duration: Infinity });
            return;
          }

          toast.error("Connection error — retrying...");
          await delay(backoffDelayMs(retries));
        }
      }
    };

    void watch();

    return () => {
      disposed = true;
      controller.abort();
    };
  }, [name, namespace, queryClient, statsKey, timeWindow.from, timeWindow.to, version]);

  useEffect(() => {
    const controller = new AbortController();
    let disposed = false;
    let retries = 0;
    let needsReconnectToast = false;

    const flushPendingInsertions = () => {
      insertionTimerRef.current = null;
      if (pendingInsertionsRef.current.length === 0) return;

      const uniqueRows = uniqueByWorkflowId(pendingInsertionsRef.current);
      pendingInsertionsRef.current = [];

      if (isWindowAtTop()) {
        prependInstances(queryClient, instancesKey, uniqueRows);
        return;
      }

      setBufferedNewInstances((previous) => uniqueByWorkflowId([...uniqueRows, ...previous]));
    };

    const scheduleFlush = () => {
      if (insertionTimerRef.current != null) return;
      insertionTimerRef.current = window.setTimeout(flushPendingInsertions, 500);
    };

    const watch = async () => {
      while (!disposed && !controller.signal.aborted) {
        try {
          const request = new WatchInstancesRequest({
            namespace,
            name,
            version,
            afterSequence: newestSequenceRef.current,
          });

          for await (const update of gatewayClient.watchInstances(request, {
            signal: controller.signal,
          })) {
            if (needsReconnectToast) {
              toast.success("Reconnected — live updates resumed", { duration: 3000 });
              needsReconnectToast = false;
            }
            retries = 0;

            newestSequenceRef.current = toBigInt(update.sequence) ?? newestSequenceRef.current;

            const updated = update.instance;
            if (!updated) continue;

            const existed = upsertExistingInstance(queryClient, instancesKey, updated);
            if (existed) continue;

            pendingInsertionsRef.current.push(new WorkflowInstance(updated));
            scheduleFlush();
          }

          if (controller.signal.aborted || disposed) {
            return;
          }

          throw new Error("Instances stream disconnected");
        } catch {
          if (controller.signal.aborted || disposed) {
            return;
          }

          retries += 1;
          needsReconnectToast = true;
          if (retries > MAX_RETRIES) {
            toast.error("Live updates unavailable. Reload page.", { duration: Infinity });
            return;
          }

          toast.error("Connection error — retrying...");
          await delay(backoffDelayMs(retries));
        }
      }
    };

    void watch();

    return () => {
      disposed = true;
      controller.abort();
      if (insertionTimerRef.current != null) {
        window.clearTimeout(insertionTimerRef.current);
        insertionTimerRef.current = null;
      }
      pendingInsertionsRef.current = [];
    };
  }, [instancesKey, name, namespace, queryClient, version]);

  const totals = useMemo(() => {
    return stats.reduce(
      (acc, stat) => {
        acc.started += asNumber(stat.started);
        acc.completed += asNumber(stat.completed);
        acc.faulted += asNumber(stat.faulted);
        acc.running += asNumber(stat.running);
        return acc;
      },
      { started: 0, completed: 0, faulted: 0, running: 0 },
    );
  }, [stats]);

  const breakdownRows = useMemo(() => buildBreakdownRows(stats, level), [stats, level]);

  const onBreakdownClick = (row: BreakdownRow) => {
    if (level === "root") {
      navigate(`/ns/${encodeURIComponent(row.label)}`);
      return;
    }
    if (level === "namespace" && namespace) {
      navigate(`/ns/${encodeURIComponent(namespace)}/name/${encodeURIComponent(row.label)}`);
      return;
    }
    if (level === "workflow" && namespace && name) {
      navigate(
        `/ns/${encodeURIComponent(namespace)}/name/${encodeURIComponent(name)}/v/${encodeURIComponent(row.label)}`,
      );
    }
  };

  const applyBufferedRows = () => {
    if (bufferedNewInstances.length === 0) return;
    prependInstances(queryClient, instancesKey, bufferedNewInstances);
    setBufferedNewInstances([]);
    window.scrollTo({ top: 0, behavior: "smooth" });
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <TimeRangeSelector value={timeRange} onChange={setTimeRange} />
        <div className="flex items-center gap-2">
          <span className="text-sm text-slate-600 dark:text-slate-300">Status</span>
          <div className="w-48">
            <Select
              value={status}
              onChange={(event) => setStatus(event.target.value)}
              options={[
                { value: "all", label: "All" },
                { value: "PENDING", label: "Pending" },
                { value: "RUNNING", label: "Running" },
                { value: "COMPLETED", label: "Completed" },
                { value: "FAULTED", label: "Faulted" },
              ]}
            />
          </div>
        </div>
      </div>

      <StatsCards
        started={totals.started}
        completed={totals.completed}
        faulted={totals.faulted}
        running={totals.running}
      />

      {level !== "version" && (
        <DefinitionTable
          title={
            level === "root"
              ? "Breakdown by namespace"
              : level === "namespace"
                ? "Breakdown by workflow name"
                : "Breakdown by version"
          }
          labelColumnTitle={
            level === "root" ? "Namespace" : level === "namespace" ? "Name" : "Version"
          }
          showLatestVersionColumn={level === "namespace"}
          latestVersionColumnTitle="Latest Version"
          rows={breakdownRows}
          onRowClick={onBreakdownClick}
          defaultSortKey="label"
          defaultSortDirection={level === "workflow" ? "desc" : "asc"}
        />
      )}

      <div className="space-y-2">
        <h3 className="text-sm font-semibold uppercase tracking-wide text-slate-600 dark:text-slate-300">
          Instances
        </h3>

        <NewInstancesBanner count={bufferedNewInstances.length} onApply={applyBufferedRows} />

        <InstanceTable
          rows={instances.instances.map((instance) => ({
            workflowId: instance.workflowId,
            namespace: instance.namespace,
            name: instance.name,
            version: instance.version,
            status: instance.status,
            startedAt: instance.startedAt,
            durationMs: instance.durationMs,
            error: instance.error,
          }))}
          showNamespace={level === "root"}
          showName={level === "root" || level === "namespace"}
          onRowClick={(workflowId) => navigate(`/id/${workflowId}`)}
          hasMore={Boolean(instances.hasNextPage)}
          isLoadingMore={instances.isFetchingNextPage}
          onLoadMore={() => void instances.fetchNextPage()}
          totalCount={instances.totalCount}
        />

        {(statsLoading || instances.isLoading) && (
          <p className="text-sm text-slate-500">Loading scope data...</p>
        )}
      </div>
    </div>
  );
}

function buildBreakdownRows(stats: DefinitionStats[], level: OverviewLevel): BreakdownRow[] {
  const map = new Map<string, BreakdownRow>();

  for (const stat of stats) {
    const key =
      level === "root"
        ? stat.namespace
        : level === "namespace"
          ? stat.name
          : stat.version ?? "unknown";

    const existing = map.get(key);
    if (!existing) {
      map.set(key, {
        key,
        label: key,
        latestVersion: level === "namespace" ? stat.version : undefined,
        started: stat.started,
        completed: stat.completed,
        faulted: stat.faulted,
        running: stat.running,
      });
      continue;
    }

    existing.started = BigInt(asNumber(existing.started) + asNumber(stat.started));
    existing.completed = BigInt(asNumber(existing.completed) + asNumber(stat.completed));
    existing.faulted = BigInt(asNumber(existing.faulted) + asNumber(stat.faulted));
    existing.running = BigInt(asNumber(existing.running) + asNumber(stat.running));
    if (level === "namespace") {
      existing.latestVersion = pickLatestVersion(existing.latestVersion, stat.version);
    }
  }

  return Array.from(map.values()).sort((a, b) => a.label.localeCompare(b.label));
}

function pickLatestVersion(current: string | undefined, candidate: string | undefined): string | undefined {
  if (!candidate) return current;
  if (!current) return candidate;
  return compareDefinitionVersions(candidate, current) >= 0 ? candidate : current;
}

function compareDefinitionVersions(left: string, right: string): number {
  return left.localeCompare(right, undefined, {
    numeric: true,
    sensitivity: "base",
  });
}

function isWindowAtTop(): boolean {
  return typeof window !== "undefined" && window.scrollY <= 80;
}

function upsertExistingInstance(
  queryClient: ReturnType<typeof useQueryClient>,
  key: readonly unknown[],
  instance: WorkflowInstance,
): boolean {
  let found = false;

  queryClient.setQueryData<InfiniteData<ListInstancesResponse>>(key, (previous) => {
    if (!previous) return previous;

    const pages = previous.pages.map((page) => new ListInstancesResponse(page));

    for (const page of pages) {
      const index = page.instances.findIndex((row) => row.workflowId === instance.workflowId);
      if (index >= 0) {
        page.instances[index] = new WorkflowInstance(instance);
        found = true;
      }
    }

    if (!found) return previous;
    return {
      ...previous,
      pages,
    };
  });

  return found;
}

function prependInstances(
  queryClient: ReturnType<typeof useQueryClient>,
  key: readonly unknown[],
  rows: WorkflowInstance[],
) {
  const orderedRows = uniqueByWorkflowId(rows)
    .sort((left, right) => right.workflowId.localeCompare(left.workflowId))
    .map((row) => new WorkflowInstance(row));

  queryClient.setQueryData<InfiniteData<ListInstancesResponse>>(key, (previous) => {
    if (!previous || previous.pages.length === 0 || orderedRows.length === 0) return previous;

    const pages = previous.pages.map((page) => new ListInstancesResponse(page));
    const firstPage = pages[0];

    const existing = new Set(firstPage.instances.map((instance) => instance.workflowId));
    const inserts = orderedRows.filter((row) => !existing.has(row.workflowId));
    if (inserts.length === 0) return previous;

    firstPage.instances = [...inserts, ...firstPage.instances];
    return {
      ...previous,
      pages,
    };
  });
}

function uniqueByWorkflowId(rows: WorkflowInstance[]): WorkflowInstance[] {
  const map = new Map<string, WorkflowInstance>();
  for (const row of rows) {
    map.set(row.workflowId, new WorkflowInstance(row));
  }
  return Array.from(map.values());
}

function backoffDelayMs(retryAttempt: number): number {
  return Math.min(1000 * 2 ** Math.max(0, retryAttempt - 1), 30_000);
}

function delay(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

function toBigInt(value: bigint | number | string | undefined): bigint | undefined {
  if (typeof value === "bigint") return value;
  if (typeof value === "number") return BigInt(value);
  if (typeof value === "string") {
    const parsed = Number(value);
    if (Number.isNaN(parsed)) return undefined;
    return BigInt(parsed);
  }
  return undefined;
}
