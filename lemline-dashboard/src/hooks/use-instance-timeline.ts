import { useQuery } from "@tanstack/react-query";
import { gatewayClient } from "../lib/grpc-client";
import { GetInstanceTimelineRequest } from "../gen/proto/lemline/gateway/v1/workflow_gateway_pb";

export function instanceTimelineQueryKey(workflowId: string | undefined, includeDefinition = true) {
  return ["instance-timeline", workflowId, includeDefinition] as const;
}

export function useInstanceTimeline(workflowId: string | undefined, includeDefinition = true) {
  return useQuery({
    queryKey: instanceTimelineQueryKey(workflowId, includeDefinition),
    enabled: Boolean(workflowId),
    queryFn: async () => {
      const response = await gatewayClient.getInstanceTimeline(
        new GetInstanceTimelineRequest({
          workflowId: workflowId ?? "",
          includeDefinition,
        }),
      );
      return response;
    },
  });
}
