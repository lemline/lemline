import { useQuery } from "@tanstack/react-query";
import { fetchDefinitionStatsSnapshot } from "../lib/gateway-api";
import type { DefinitionStatsSnapshot } from "../lib/gateway-api";

export interface DefinitionStatsFilter {
  namespace?: string;
  name?: string;
  version?: string;
  timeFrom: string;
  timeTo: string;
}

export function definitionStatsQueryKey(filter: DefinitionStatsFilter) {
  return [
    "definition-stats",
    filter.namespace,
    filter.name,
    filter.version,
    filter.timeFrom,
    filter.timeTo,
  ] as const;
}

export function useDefinitionStats(filter: DefinitionStatsFilter) {
  return useQuery<DefinitionStatsSnapshot>({
    queryKey: definitionStatsQueryKey(filter),
    queryFn: () => fetchDefinitionStatsSnapshot(filter),
  });
}
