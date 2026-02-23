import { useQuery } from "@tanstack/react-query";
import { gatewayClient } from "../lib/grpc-client";
import { GetDefinitionStatsRequest } from "../gen/proto/lemline/gateway/v1/workflow_gateway_pb";

export interface DefinitionStatsFilter {
  namespace?: string;
  name?: string;
  version?: string;
  timeFrom?: string;
  timeTo?: string;
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
  return useQuery({
    queryKey: definitionStatsQueryKey(filter),
    queryFn: async () => {
      const response = await gatewayClient.getDefinitionStats(
        new GetDefinitionStatsRequest({
          namespace: filter.namespace,
          name: filter.name,
          version: filter.version,
          timeFrom: filter.timeFrom,
          timeTo: filter.timeTo,
        }),
      );
      return response.stats;
    },
  });
}
