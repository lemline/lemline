import { useInfiniteQuery } from "@tanstack/react-query";
import { gatewayClient } from "../lib/grpc-client";
import { ListInstancesRequest } from "../gen/proto/lemline/gateway/v1/workflow_gateway_pb";

export interface InstanceFilters {
  namespace?: string;
  name?: string;
  version?: string;
  status?: string;
  timeFrom?: string;
  timeTo?: string;
  workflowId?: string;
  workflowIdPrefix?: string;
  pageSize?: number;
  enabled?: boolean;
}

export function instancesQueryKey(filters: InstanceFilters) {
  return [
    "instances",
    filters.namespace,
    filters.name,
    filters.version,
    filters.status,
    filters.timeFrom,
    filters.timeTo,
    filters.workflowId,
    filters.workflowIdPrefix,
    filters.pageSize,
  ] as const;
}

export function useInstances(filters: InstanceFilters) {
  const query = useInfiniteQuery({
    queryKey: instancesQueryKey(filters),
    enabled: filters.enabled ?? true,
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) => {
      const response = await gatewayClient.listInstances(
        new ListInstancesRequest({
          namespace: filters.namespace,
          name: filters.name,
          version: filters.version,
          status: filters.status,
          timeFrom: filters.timeFrom,
          timeTo: filters.timeTo,
          workflowId: filters.workflowId,
          workflowIdPrefix: filters.workflowIdPrefix,
          pageSize: filters.pageSize ?? 50,
          pageCursor: pageParam,
        }),
      );
      return response;
    },
    getNextPageParam: (lastPage) => lastPage.nextCursor || undefined,
  });

  return {
    ...query,
    instances: query.data?.pages.flatMap((page) => page.instances) ?? [],
    totalCount: query.data?.pages[0]?.totalCount,
    nextCursor: query.data?.pages[query.data.pages.length - 1]?.nextCursor,
  };
}
