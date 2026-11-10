import { useQueryClient } from "@tanstack/react-query";
import type { InfiniteData, QueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useRef, useState } from "react";
import { toast } from "sonner";
import { useNavigate } from "react-router-dom";
import {
  DefinitionStats,
  DefinitionStatsChangeType,
  InstanceChangeType,
  InstanceStatus,
  InstanceSummary,
  ListInstancesResponse,
  WatchDefinitionStatsRequest,
  WatchInstancesRequest,
} from "../gen/proto/lemline/gateway/v1/gateway_pb";
import { useDefinitionStats, definitionStatsQueryKey } from "../hooks/use-definition-stats";
import { useInstances, instancesQueryKey } from "../hooks/use-instances";
import { gatewayClient } from "../lib/grpc-client";
import { toTimeWindow } from "../lib/time-range";
import type { TimeRangePreset } from "../lib/time-range";
import { timestampFromIsoString, timestampToMilliseconds, UNIX_EPOCH_ISO } from "../lib/proto";
import type { DefinitionStatsSnapshot } from "../lib/gateway-api";
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
  const [bufferedNewInstances, setBufferedNewInstances] = useState<InstanceSummary[]>([]);

  const timeWindow = useMemo(() => toTimeWindow(timeRange), [timeRange]);
  // The stats API requires a lower bound, so "all" starts at the epoch. No upper bound is sent:
  // the counters keep following new instances, like the live instance table.
  const statsTimeFrom = timeWindow.from ?? UNIX_EPOCH_ISO;

  const statsKey = useMemo(
    () =>
      definitionStatsQueryKey({
        namespace,
        name,
        version,
        timeFrom: statsTimeFrom,
      }),
    [name, namespace, statsTimeFrom, version],
  );

  const instanceFilters = useMemo(
    () => ({
      namespace,
      name,
      version,
      status: status === "all" ? undefined : status,
      timeFrom: timeWindow.from,
      pageSize: 50,
    }),
    [name, namespace, status, timeWindow.from, version],
  );

  const instancesKey = useMemo(() => instancesQueryKey(instanceFilters), [instanceFilters]);

  const statsQuery = useDefinitionStats({
    namespace,
    name,
    version,
    timeFrom: statsTimeFrom,
  });
  const stats = useMemo(() => statsQuery.data?.stats ?? [], [statsQuery.data?.stats]);
  const hasStatsSnapshot = Boolean(statsQuery.data);
  const statsWatchCursor = statsQuery.data?.watchCursor;

  const instances = useInstances(instanceFilters);
  const hasInstanceSnapshot = Boolean(instances.data?.pages[0]);
  const instancesWatchCursor = instances.watchCursor;

  const pendingInsertionsRef = useRef<InstanceSummary[]>([]);
  const insertionTimerRef = useRef<number | null>(null);

  useEffect(() => {
    setBufferedNewInstances([]);
    pendingInsertionsRef.current = [];
  }, [instancesKey]);

  useEffect(() => {
    if (statsQuery.status !== "success" || !hasStatsSnapshot) return;

    const controller = new AbortController();
    let disposed = false;
    let retries = 0;
    let needsReconnectToast = false;
    let afterCursor = statsWatchCursor;

    const watch = async () => {
      while (!disposed && !controller.signal.aborted) {
        try {
          const request = new WatchDefinitionStatsRequest({
            namespace,
            name,
            version,
            timeFrom: timestampFromIsoString(statsTimeFrom),
            afterCursor,
          });

          for await (const update of gatewayClient.watchDefinitionStats(request, {
            signal: controller.signal,
          })) {
            if (needsReconnectToast) {
              toast.success("Reconnected — live updates resumed", { duration: 3000 });
              needsReconnectToast = false;
            }
            retries = 0;
            afterCursor = update.cursor || afterCursor;

            queryClient.setQueryData<DefinitionStatsSnapshot>(statsKey, (previous) =>
              applyDefinitionStatsChange(previous, update),
            );
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
  }, [hasStatsSnapshot, name, namespace, queryClient, statsKey, statsQuery.status, statsTimeFrom, statsWatchCursor, version]);

  useEffect(() => {
    if (instances.status !== "success" || !hasInstanceSnapshot) return;

    const controller = new AbortController();
    let disposed = false;
    let retries = 0;
    let needsReconnectToast = false;
    let afterCursor = instancesWatchCursor;

    const flushPendingInsertions = () => {
      insertionTimerRef.current = null;
      if (pendingInsertionsRef.current.length === 0) return;

      const uniqueRows = uniqueByInstanceId(pendingInsertionsRef.current);
      pendingInsertionsRef.current = [];

      if (isWindowAtTop()) {
        prependInstances(queryClient, instancesKey, uniqueRows);
        return;
      }

      setBufferedNewInstances((previous) => uniqueByInstanceId([...uniqueRows, ...previous]));
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
            status: toProtoInstanceStatus(status),
            createdFrom: timestampFromIsoString(timeWindow.from),
            afterCursor,
          });

          for await (const update of gatewayClient.watchInstances(request, {
            signal: controller.signal,
          })) {
            if (needsReconnectToast) {
              toast.success("Reconnected — live updates resumed", { duration: 3000 });
              needsReconnectToast = false;
            }
            retries = 0;
            afterCursor = update.cursor || afterCursor;

            const change = update.change;
            const changedInstance = change?.instance;
            if (!changedInstance) continue;

            if (change?.changeType === InstanceChangeType.REMOVED) {
              removeInstance(queryClient, instancesKey, changedInstance.instanceId);
              pendingInsertionsRef.current = pendingInsertionsRef.current.filter(
                (row) => row.instanceId !== changedInstance.instanceId,
              );
              setBufferedNewInstances((previous) =>
                previous.filter((row) => row.instanceId !== changedInstance.instanceId),
              );
              continue;
            }

            const existed = upsertExistingInstance(queryClient, instancesKey, changedInstance);
            if (existed) continue;

            pendingInsertionsRef.current.push(new InstanceSummary(changedInstance));
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
  }, [hasInstanceSnapshot, instances.status, instancesKey, instancesWatchCursor, name, namespace, queryClient, status, timeWindow.from, version]);

  const totals = useMemo(() => {
    return stats.reduce(
      (acc, stat) => {
        acc.started += asNumber(stat.startedCount);
        acc.completed += asNumber(stat.completedCount);
        acc.faulted += asNumber(stat.faultedCount);
        acc.running += asNumber(stat.runningCountAtTimeTo);
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
        `/ns/${encodeURIComponent(namespace)}/name/${encodeURIComponent(name)}/version/${encodeURIComponent(row.label)}`,
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
            workflowId: instance.instanceId,
            namespace: instance.namespace,
            name: instance.name,
            version: instance.version,
            status: instance.status,
            startedAt: instance.startedAt,
            durationMs: instance.duration,
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

        {(statsQuery.isLoading || instances.isLoading) && (
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
          : stat.version || "unknown";

    const existing = map.get(key);
    if (!existing) {
      map.set(key, {
        key,
        label: key,
        latestVersion: level === "namespace" ? stat.version : undefined,
        started: stat.startedCount,
        completed: stat.completedCount,
        faulted: stat.faultedCount,
        running: stat.runningCountAtTimeTo,
      });
      continue;
    }

    existing.started = BigInt(asNumber(existing.started) + asNumber(stat.startedCount));
    existing.completed = BigInt(asNumber(existing.completed) + asNumber(stat.completedCount));
    existing.faulted = BigInt(asNumber(existing.faulted) + asNumber(stat.faultedCount));
    existing.running = BigInt(asNumber(existing.running) + asNumber(stat.runningCountAtTimeTo));
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

function applyDefinitionStatsChange(
  previous: DefinitionStatsSnapshot | undefined,
  update: { cursor: string; change?: { changeType: DefinitionStatsChangeType; stats?: DefinitionStats } },
): DefinitionStatsSnapshot {
  const nextStats = (previous?.stats ?? []).map((row) => new DefinitionStats(row));
  const changedStats = update.change?.stats;

  if (changedStats) {
    const index = nextStats.findIndex((row) => matchesDefinitionStatsKey(row, changedStats));
    if (update.change?.changeType === DefinitionStatsChangeType.REMOVED) {
      if (index >= 0) nextStats.splice(index, 1);
    } else if (index >= 0) {
      nextStats[index] = new DefinitionStats(changedStats);
    } else {
      nextStats.push(new DefinitionStats(changedStats));
    }
  }

  return {
    stats: nextStats,
    totalCount: previous?.totalCount,
    watchCursor: previous?.watchCursor,
  };
}

function matchesDefinitionStatsKey(left: DefinitionStats, right: DefinitionStats): boolean {
  return left.namespace === right.namespace && left.name === right.name && left.version === right.version;
}

function upsertExistingInstance(
  queryClient: QueryClient,
  key: readonly unknown[],
  instance: InstanceSummary,
): boolean {
  let found = false;

  queryClient.setQueryData<InfiniteData<ListInstancesResponse>>(key, (previous) => {
    if (!previous) return previous;

    const pages = previous.pages.map((page) => new ListInstancesResponse(page));

    for (const page of pages) {
      const index = page.instances.findIndex((row) => row.instanceId === instance.instanceId);
      if (index >= 0) {
        page.instances[index] = new InstanceSummary(instance);
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

function removeInstance(queryClient: QueryClient, key: readonly unknown[], instanceId: string) {
  queryClient.setQueryData<InfiniteData<ListInstancesResponse>>(key, (previous) => {
    if (!previous) return previous;

    let changed = false;
    const pages = previous.pages.map((page) => {
      const clone = new ListInstancesResponse(page);
      const nextInstances = clone.instances.filter((row) => row.instanceId !== instanceId);
      if (nextInstances.length !== clone.instances.length) {
        changed = true;
        clone.instances = nextInstances;
      }
      return clone;
    });

    if (!changed) return previous;
    return {
      ...previous,
      pages,
    };
  });
}

function prependInstances(
  queryClient: QueryClient,
  key: readonly unknown[],
  rows: InstanceSummary[],
) {
  const orderedRows = uniqueByInstanceId(rows)
    .sort(compareInstancesDesc)
    .map((row) => new InstanceSummary(row));

  queryClient.setQueryData<InfiniteData<ListInstancesResponse>>(key, (previous) => {
    if (!previous || previous.pages.length === 0 || orderedRows.length === 0) return previous;

    const pages = previous.pages.map((page) => new ListInstancesResponse(page));
    const firstPage = pages[0];

    const combined = [...orderedRows, ...firstPage.instances].sort(compareInstancesDesc);
    const deduplicated = uniqueByInstanceId(combined);
    const existingIds = firstPage.instances.map((instance) => instance.instanceId).join(",");
    const nextIds = deduplicated.map((instance) => instance.instanceId).join(",");
    if (existingIds === nextIds) return previous;

    firstPage.instances = deduplicated;
    return {
      ...previous,
      pages,
    };
  });
}

function uniqueByInstanceId(rows: InstanceSummary[]): InstanceSummary[] {
  const map = new Map<string, InstanceSummary>();
  for (const row of rows) {
    map.set(row.instanceId, new InstanceSummary(row));
  }
  return Array.from(map.values());
}

function compareInstancesDesc(left: InstanceSummary, right: InstanceSummary): number {
  const leftCreatedAt = timestampToMilliseconds(left.createdAt) ?? 0;
  const rightCreatedAt = timestampToMilliseconds(right.createdAt) ?? 0;
  if (leftCreatedAt !== rightCreatedAt) {
    return rightCreatedAt - leftCreatedAt;
  }
  return right.instanceId.localeCompare(left.instanceId);
}

function toProtoInstanceStatus(value: string): InstanceStatus | undefined {
  switch (value) {
    case "PENDING":
      return InstanceStatus.PENDING;
    case "RUNNING":
      return InstanceStatus.RUNNING;
    case "COMPLETED":
      return InstanceStatus.COMPLETED;
    case "FAULTED":
      return InstanceStatus.FAULTED;
    default:
      return undefined;
  }
}

function backoffDelayMs(retryAttempt: number): number {
  return Math.min(1000 * 2 ** Math.max(0, retryAttempt - 1), 30_000);
}

function delay(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}
