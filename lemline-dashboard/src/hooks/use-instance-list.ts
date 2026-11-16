import { create } from "@bufbuild/protobuf";
import { type InfiniteData, useInfiniteQuery } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import {
  InstanceStatus,
  type InstanceSummary,
  type ListInstancesResponse,
  ListInstancesRequestSchema,
  WatchInstancesRequestSchema,
  type WatchInstancesResponse,
} from "../gen/proto/lemline/gateway/v1/gateway_pb";
import { gatewayClient } from "../lib/grpc-client";
import { timestampFromIsoString, timestampToMilliseconds } from "../lib/proto";
import { asNumber } from "../lib/utils";
import { UI_STATUSES, type UiStatus } from "../lib/status";
import { useLiveInvalidation } from "./use-live-invalidation";

/** Fallback refresh when the change stream is unavailable. */
const REFRESH_MS = 5_000;
/** Pages loaded at most for the periodic refresh: going deep in the list stops it. */
const REFRESHED_PAGES = 2;

/** The gateway statuses behind each status a person sees: a pending instance is shown as running. */
const SERVER_STATUSES: Record<UiStatus, InstanceStatus[]> = {
  running: [InstanceStatus.PENDING, InstanceStatus.RUNNING],
  faulted: [InstanceStatus.FAULTED],
  completed: [InstanceStatus.COMPLETED],
};

export interface InstanceListFilters {
  namespace?: string;
  name?: string;
  version?: string;
  statuses: UiStatus[];
  timeFrom?: string;
  perPage: number;
  /** 0-based page shown. */
  page: number;
}

interface StreamFilters {
  namespace?: string;
  name?: string;
  version?: string;
  timeFrom?: string;
  status?: InstanceStatus;
  pageSize: number;
  needed: number;
  refresh: boolean;
  enabled: boolean;
}

/** One list of instances filtered by the gateway, newest first, loaded until `needed` rows. */
function useInstanceStream(filters: StreamFilters) {
  const { namespace, name, version, timeFrom, status, pageSize, needed, refresh, enabled } = filters;
  const queryKey = ["instance-list", namespace, name, version, status, timeFrom, pageSize];
  const identity = JSON.stringify(queryKey);
  const [lostIdentity, setLostIdentity] = useState<string | null>(null);
  const polling = lostIdentity === identity;
  const query = useInfiniteQuery<ListInstancesResponse, Error, InfiniteData<ListInstancesResponse>, unknown[], string | undefined>({
    queryKey,
    enabled,
    initialPageParam: undefined as string | undefined,
    refetchInterval: (current) =>
      polling && refresh && (current.state.data?.pages.length ?? 0) <= REFRESHED_PAGES ? REFRESH_MS : false,
    queryFn: async ({ pageParam, signal }) =>
      gatewayClient.listInstances(
        create(ListInstancesRequestSchema, {
          namespace: namespace || undefined,
          name: name || undefined,
          version: version || undefined,
          status,
          createdFrom: timestampFromIsoString(timeFrom),
          pageSize,
          pageCursor: pageParam,
        }),
        { signal },
      ),
    getNextPageParam: (lastPage) => lastPage.nextPageCursor || undefined,
  });

  // Each change of an instance in this list (new, finished, faulted) refreshes it at once.
  // The stream stays open while rows are shown, even if one refresh failed: the next change
  // retries it, and a stream lost for good switches to polling.
  useLiveInvalidation<WatchInstancesResponse>({
    enabled: enabled && query.data !== undefined,
    queryKey,
    identity,
    onLost: setLostIdentity,
    initialCursor: query.data?.pages[0]?.watchCursor,
    cursorOf: (message) => message.cursor,
    watch: (cursor, signal) =>
      gatewayClient.watchInstances(
        create(WatchInstancesRequestSchema, {
          namespace: namespace || undefined,
          name: name || undefined,
          version: version || undefined,
          status,
          createdFrom: timestampFromIsoString(timeFrom),
          afterCursor: cursor,
        }),
        { signal },
      ),
  });

  const rows = useMemo(() => query.data?.pages.flatMap((response) => response.instances) ?? [], [query.data?.pages]);
  const { hasNextPage, isFetching, isFetchNextPageError, fetchNextPage } = query;
  // After a page failed (retries included), loading stops until the person asks to retry.
  useEffect(() => {
    if (enabled && rows.length < needed && hasNextPage && !isFetching && !isFetchNextPageError) void fetchNextPage();
  }, [enabled, fetchNextPage, hasNextPage, isFetching, isFetchNextPageError, needed, rows.length]);

  const total = query.data?.pages[0]?.totalCount;
  return {
    rows,
    total: total !== undefined ? asNumber(total) : !hasNextPage && query.isSuccess ? rows.length : undefined,
    /** Every row up to `needed` is loaded (or there are no more). */
    covered: !enabled || (query.isSuccess && (rows.length >= needed || !hasNextPage)),
    isLoading: enabled && query.isLoading,
    isError: query.isError,
    refetch: query.refetch,
  };
}

function compareNewestFirst(left: InstanceSummary, right: InstanceSummary): number {
  const leftMs = timestampToMilliseconds(left.createdAt) ?? 0;
  const rightMs = timestampToMilliseconds(right.createdAt) ?? 0;
  return rightMs - leftMs || right.instanceId.localeCompare(left.instanceId);
}

/**
 * Instances shown page by page. The gateway pages with cursors and filters on one status, so:
 * all statuses = one unfiltered list; otherwise one filtered list per gateway status behind the
 * statuses selected (Running is Pending + Running), merged by creation time. That is at most three
 * lists, for two statuses selected. Only the rows up to the page shown are loaded.
 */
export function useInstanceList(filters: InstanceListFilters) {
  const { namespace, name, version, statuses, timeFrom, perPage, page } = filters;
  const all = statuses.length === UI_STATUSES.length;
  const needed = (page + 1) * perPage;
  const common = { namespace, name, version, timeFrom, pageSize: Math.max(25, perPage), needed, refresh: page === 0 };
  const serverStatuses: (InstanceStatus | undefined)[] = all ? [undefined] : statuses.flatMap((status) => SERVER_STATUSES[status]);

  // A fixed number of lists, so the hooks are called the same way on every render.
  const streams = [
    useInstanceStream({ ...common, status: serverStatuses[0], enabled: serverStatuses.length > 0 }),
    useInstanceStream({ ...common, status: serverStatuses[1], enabled: serverStatuses.length > 1 }),
    useInstanceStream({ ...common, status: serverStatuses[2], enabled: serverStatuses.length > 2 }),
  ];
  const active = streams.slice(0, serverStatuses.length);
  const merged = active.length > 1;
  const [first, second, third] = streams;

  const rows = useMemo<InstanceSummary[]>(() => {
    // No status selected: nothing, even though a disabled list still holds its earlier rows.
    if (active.length === 0) return [];
    const list = merged ? [first.rows, second.rows, third.rows].slice(0, active.length).flat() : first.rows;
    const unique = new Map<string, InstanceSummary>();
    for (const instance of list) unique.set(instance.instanceId, instance);
    const result = Array.from(unique.values());
    return merged ? result.sort(compareNewestFirst) : result;
  }, [active.length, first.rows, merged, second.rows, third.rows]);

  const totals = active.map((stream) => stream.total);
  const exactTotal = totals.length > 0 && totals.every((total) => total !== undefined)
    ? totals.reduce<number>((sum, total) => sum + (total ?? 0), 0)
    : undefined;
  const covered = streams.every((stream) => stream.covered);
  const enabled = statuses.length > 0;

  return {
    rows,
    // Merged lists are only exact once all of them are loaded past the page shown.
    pageRows: covered || !merged ? rows.slice(page * perPage, page * perPage + perPage) : [],
    exactTotal,
    loadingPage: enabled && !active.some((stream) => stream.isError) && (active.some((stream) => stream.isLoading) || !covered),
    isError: active.some((stream) => stream.isError),
    refetch: () => {
      for (const stream of active) void stream.refetch();
    },
    enabled,
  };
}
