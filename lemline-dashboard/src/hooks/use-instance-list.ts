import { create } from "@bufbuild/protobuf";
import { type InfiniteData, useInfiniteQuery } from "@tanstack/react-query";
import { useEffect, useMemo } from "react";
import {
  InstanceStatus,
  type InstanceSummary,
  type ListInstancesResponse,
  ListInstancesRequestSchema,
} from "../gen/proto/lemline/gateway/v1/gateway_pb";
import { gatewayClient } from "../lib/grpc-client";
import { timestampFromIsoString, timestampToMilliseconds } from "../lib/proto";
import { asNumber } from "../lib/utils";
import { UI_STATUSES, type UiStatus } from "../lib/status";

const REFRESH_MS = 5_000;
/** Pages loaded at most for the periodic refresh: going deep in the list stops it. */
const REFRESHED_PAGES = 2;

const SERVER_STATUS: Record<UiStatus, InstanceStatus> = {
  running: InstanceStatus.RUNNING,
  faulted: InstanceStatus.FAULTED,
  completed: InstanceStatus.COMPLETED,
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
  const query = useInfiniteQuery<ListInstancesResponse, Error, InfiniteData<ListInstancesResponse>, unknown[], string | undefined>({
    queryKey: ["instance-list", namespace, name, version, status, timeFrom, pageSize],
    enabled,
    initialPageParam: undefined as string | undefined,
    refetchInterval: (current) =>
      refresh && (current.state.data?.pages.length ?? 0) <= REFRESHED_PAGES ? REFRESH_MS : false,
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

  const rows = useMemo(() => query.data?.pages.flatMap((response) => response.instances) ?? [], [query.data?.pages]);
  const { hasNextPage, isFetching, fetchNextPage } = query;
  useEffect(() => {
    if (enabled && rows.length < needed && hasNextPage && !isFetching) void fetchNextPage();
  }, [enabled, fetchNextPage, hasNextPage, isFetching, needed, rows.length]);

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
 * all statuses = one unfiltered list; one status = one filtered list; two statuses = two
 * filtered lists merged by creation time. Only the rows up to the page shown are loaded.
 */
export function useInstanceList(filters: InstanceListFilters) {
  const { namespace, name, version, statuses, timeFrom, perPage, page } = filters;
  const all = statuses.length === UI_STATUSES.length;
  const needed = (page + 1) * perPage;
  const common = { namespace, name, version, timeFrom, pageSize: Math.max(25, perPage), needed, refresh: page === 0 };

  const first = useInstanceStream({
    ...common,
    status: all || statuses.length === 0 ? undefined : SERVER_STATUS[statuses[0]],
    enabled: statuses.length > 0,
  });
  const second = useInstanceStream({
    ...common,
    status: statuses.length === 2 ? SERVER_STATUS[statuses[1]] : undefined,
    enabled: statuses.length === 2,
  });
  const merged = statuses.length === 2;

  const rows = useMemo<InstanceSummary[]>(() => {
    const list = merged ? [...first.rows, ...second.rows] : first.rows;
    const unique = new Map<string, InstanceSummary>();
    for (const instance of list) unique.set(instance.instanceId, instance);
    const result = Array.from(unique.values());
    return merged ? result.sort(compareNewestFirst) : result;
  }, [first.rows, merged, second.rows]);

  const exactTotal = merged
    ? first.total !== undefined && second.total !== undefined
      ? first.total + second.total
      : undefined
    : first.total;
  const covered = first.covered && second.covered;
  const enabled = statuses.length > 0;

  return {
    rows,
    // Merged lists are only exact once both are loaded past the page shown.
    pageRows: covered || !merged ? rows.slice(page * perPage, page * perPage + perPage) : [],
    exactTotal,
    loadingPage: enabled && (first.isLoading || second.isLoading || !covered),
    isError: first.isError || second.isError,
    refetch: () => {
      void first.refetch();
      if (merged) void second.refetch();
    },
    enabled,
  };
}
