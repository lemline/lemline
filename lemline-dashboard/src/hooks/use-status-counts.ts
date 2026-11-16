import { create } from "@bufbuild/protobuf";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import {
  InstanceStatus,
  ListInstancesRequestSchema,
  WatchDefinitionStatsRequestSchema,
  type WatchDefinitionStatsResponse,
  WatchInstancesRequestSchema,
  type WatchInstancesResponse,
} from "../gen/proto/lemline/gateway/v1/gateway_pb";
import { gatewayClient } from "../lib/grpc-client";
import { timestampFromIsoString } from "../lib/proto";
import { useLiveInvalidation } from "./use-live-invalidation";
import { fetchDefinitionStatsSnapshot } from "../lib/gateway-api";
import { UNIX_EPOCH_ISO } from "../lib/proto";
import { asNumber } from "../lib/utils";

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
    enabled: enabled && query.data !== undefined,
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

/** Gateway statuses counted, in this order: a pending instance is counted as running. */
const COUNTED = [InstanceStatus.PENDING, InstanceStatus.RUNNING, InstanceStatus.FAULTED, InstanceStatus.COMPLETED];

/**
 * Running, faulted and completed instances created in the period, for a scope. Counted with the
 * same filters as the instance list, so a counter always matches the list its status shows.
 */
export function useStatusCounts(filter: StatsFilter) {
  const { namespace, name, version, timeFrom } = filter;
  const queryKey = ["instance-counts", namespace, name, version, timeFrom];
  const identity = JSON.stringify(queryKey);
  const [lostIdentity, setLostIdentity] = useState<string | null>(null);
  const polling = lostIdentity === identity;
  const query = useQuery({
    queryKey,
    refetchInterval: polling ? REFRESH_MS : false,
    queryFn: async ({ signal }) => {
      const responses = await Promise.all(
        COUNTED.map((status) =>
          gatewayClient.listInstances(
            create(ListInstancesRequestSchema, {
              namespace: namespace || undefined,
              name: name || undefined,
              version: version || undefined,
              status,
              createdFrom: timestampFromIsoString(timeFrom),
              pageSize: 1,
            }),
            { signal },
          ),
        ),
      );
      const [pending, running, faulted, completed] = responses.map((response) => asNumber(response.totalCount));
      const counts: StatusCounts = {
        running: pending + running,
        faulted,
        completed,
        all: pending + running + faulted + completed,
      };
      return { counts, watchCursor: responses[0].watchCursor };
    },
  });
  // One change stream for the four counts: any instance of the scope that changes refreshes them.
  useLiveInvalidation<WatchInstancesResponse>({
    enabled: query.data !== undefined,
    queryKey,
    identity,
    onLost: setLostIdentity,
    initialCursor: query.data?.watchCursor,
    cursorOf: (message) => message.cursor,
    watch: (cursor, signal) =>
      gatewayClient.watchInstances(
        create(WatchInstancesRequestSchema, {
          namespace: namespace || undefined,
          name: name || undefined,
          version: version || undefined,
          createdFrom: timestampFromIsoString(timeFrom),
          afterCursor: cursor,
        }),
        { signal },
      ),
  });
  return { counts: query.data?.counts, isLoading: query.isLoading };
}
