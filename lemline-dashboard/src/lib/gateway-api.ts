import type { DefinitionStats, DefinitionSummary } from "../gen/proto/lemline/gateway/v1/gateway_pb";
import {
  ListDefinitionsRequest,
  ListDefinitionStatsRequest,
} from "../gen/proto/lemline/gateway/v1/gateway_pb";
import { gatewayClient } from "./grpc-client";
import { timestampFromIsoString } from "./proto";

const SNAPSHOT_PAGE_SIZE = 200;

interface FetchDefinitionsOptions {
  namespace?: string;
  name?: string;
  latestOnly?: boolean;
}

export interface DefinitionStatsSnapshot {
  stats: DefinitionStats[];
  totalCount?: bigint;
  watchCursor?: string;
}

interface FetchDefinitionStatsOptions {
  namespace?: string;
  name?: string;
  version?: string;
  timeFrom: string;
  timeTo: string;
}

export async function fetchAllDefinitions(
  options: FetchDefinitionsOptions = {},
): Promise<DefinitionSummary[]> {
  const definitions: DefinitionSummary[] = [];
  let pageCursor: string | undefined;

  do {
    const response = await gatewayClient.listDefinitions(
      new ListDefinitionsRequest({
        namespace: options.namespace,
        name: options.name,
        latestOnly: options.latestOnly,
        pageSize: SNAPSHOT_PAGE_SIZE,
        pageCursor,
      }),
    );

    definitions.push(...response.definitions);
    pageCursor = response.nextPageCursor;
  } while (pageCursor);

  return definitions;
}

export async function fetchDefinitionStatsSnapshot(
  options: FetchDefinitionStatsOptions,
): Promise<DefinitionStatsSnapshot> {
  const stats: DefinitionStats[] = [];
  let pageCursor: string | undefined;
  let totalCount: bigint | undefined;
  let watchCursor: string | undefined;

  do {
    const response = await gatewayClient.listDefinitionStats(
      new ListDefinitionStatsRequest({
        namespace: options.namespace,
        name: options.name,
        version: options.version,
        timeFrom: timestampFromIsoString(options.timeFrom),
        timeTo: timestampFromIsoString(options.timeTo),
        pageSize: SNAPSHOT_PAGE_SIZE,
        pageCursor,
      }),
    );

    stats.push(...response.stats);
    totalCount = response.totalCount ?? totalCount;
    watchCursor = response.watchCursor ?? watchCursor;
    pageCursor = response.nextPageCursor;
  } while (pageCursor);

  return {
    stats,
    totalCount,
    watchCursor,
  };
}
