import { create } from "@bufbuild/protobuf";
import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { WatchDefinitionStatsRequestSchema, type WatchDefinitionStatsResponse } from "../gen/proto/lemline/gateway/v1/gateway_pb";
import { gatewayClient } from "../lib/grpc-client";
import { timestampFromIsoString } from "../lib/proto";
import { useLiveInvalidation } from "./use-live-invalidation";
import { fetchDefinitionStatsSnapshot } from "../lib/gateway-api";
import { UNIX_EPOCH_ISO } from "../lib/proto";
import { asNumber } from "../lib/utils";
import type { DefinitionStats } from "../gen/proto/lemline/gateway/v1/gateway_pb";

/** Fallback refresh when the change stream is unavailable. */
const REFRESH_MS = 5_000;

export interface StatusCounts {
  running: number;
  faulted: number;
  completed: number;
  all: number;
}

export interface StatsFilter {
  namespace?: string;
  name?: string;
  version?: string;
  /** Undefined: since the beginning. */
  timeFrom?: string;
}

export function useDefinitionStatsRows(filter: StatsFilter, enabled = true) {
  const timeFrom = filter.timeFrom ?? UNIX_EPOCH_ISO;
  const queryKey = ["status-counts", filter.namespace, filter.name, filter.version, timeFrom];
  const identity = JSON.stringify(queryKey);
  const [lostIdentity, setLostIdentity] = useState<string | null>(null);
  const polling = lostIdentity === identity;
  const query = useQuery({
    queryKey,
    enabled,
    refetchInterval: polling ? REFRESH_MS : false,
    queryFn: () =>
      fetchDefinitionStatsSnapshot({
        namespace: filter.namespace || undefined,
        name: filter.name || undefined,
        version: filter.version || undefined,
        timeFrom,
      }),
  });
  // Counters follow the gateway's change stream; polling only if the stream is lost.
  useLiveInvalidation<WatchDefinitionStatsResponse>({
    enabled: enabled && query.isSuccess,
    queryKey,
    identity,
    onLost: setLostIdentity,
    initialCursor: query.data?.watchCursor,
    cursorOf: (message) => message.cursor,
    watch: (cursor, signal) =>
      gatewayClient.watchDefinitionStats(
        create(WatchDefinitionStatsRequestSchema, {
          namespace: filter.namespace || undefined,
          name: filter.name || undefined,
          version: filter.version || undefined,
          timeFrom: timestampFromIsoString(timeFrom),
          afterCursor: cursor,
        }),
        { signal },
      ),
  });
  return query;
}

export function sumCounts(stats: DefinitionStats[]): StatusCounts {
  const counts = stats.reduce(
    (acc, stat) => {
      acc.running += asNumber(stat.runningCountAtTimeTo);
      acc.faulted += asNumber(stat.faultedCount);
      acc.completed += asNumber(stat.completedCount);
      return acc;
    },
    { running: 0, faulted: 0, completed: 0 },
  );
  return { ...counts, all: counts.running + counts.faulted + counts.completed };
}

/** Running, faulted and completed instances created in the period, for a scope. */
export function useStatusCounts(filter: StatsFilter) {
  const query = useDefinitionStatsRows(filter);
  const counts = useMemo(() => (query.data ? sumCounts(query.data.stats) : undefined), [query.data]);
  return { counts, isLoading: query.isLoading };
}
