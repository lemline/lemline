import { useQuery } from "@tanstack/react-query";
import { gatewayClient } from "../lib/grpc-client";
import { GetDefinitionRequest } from "../gen/proto/lemline/gateway/v1/workflow_gateway_pb";

export function useDefinition(namespace?: string, name?: string, version?: string) {
  return useQuery({
    queryKey: ["definition", namespace, name, version],
    enabled: Boolean(namespace && name),
    queryFn: async () => {
      const response = await gatewayClient.getDefinition(
        new GetDefinitionRequest({
          namespace: namespace ?? "",
          name: name ?? "",
          version,
        }),
      );
      return response;
    },
  });
}
