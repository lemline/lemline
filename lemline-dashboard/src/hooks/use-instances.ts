import { create } from "@bufbuild/protobuf";
import { useInfiniteQuery } from "@tanstack/react-query";
import { InstanceStatus, ListInstancesRequestSchema } from "../gen/proto/lemline/gateway/v1/gateway_pb";
import { gatewayClient } from "../lib/grpc-client";
import { timestampFromIsoString } from "../lib/proto";

export interface InstanceFilters {
  namespace?: string;
  name?: string;
  version?: string;
  status?: string | InstanceStatus;
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
        create(ListInstancesRequestSchema, {
          namespace: filters.namespace,
          name: filters.name,
          version: filters.version,
          status: toProtoInstanceStatus(filters.status),
          createdFrom: timestampFromIsoString(filters.timeFrom),
          createdTo: timestampFromIsoString(filters.timeTo),
          instanceIdPrefix: filters.workflowId ?? filters.workflowIdPrefix,
          pageSize: filters.pageSize ?? 50,
          pageCursor: pageParam,
        }),
      );
      return response;
    },
    getNextPageParam: (lastPage) => lastPage.nextPageCursor || undefined,
  });

  return {
    ...query,
    instances: query.data?.pages.flatMap((page) => page.instances) ?? [],
    totalCount: query.data?.pages[0]?.totalCount,
    nextCursor: query.data?.pages[query.data.pages.length - 1]?.nextPageCursor,
    watchCursor: query.data?.pages[0]?.watchCursor,
  };
}

function toProtoInstanceStatus(
  value: InstanceFilters["status"],
): InstanceStatus | undefined {
  if (typeof value === "number") {
    return value === InstanceStatus.UNSPECIFIED ? undefined : value;
  }

  switch (value?.toUpperCase()) {
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
