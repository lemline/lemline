import { create } from "@bufbuild/protobuf";
import { useQuery } from "@tanstack/react-query";
import { gatewayClient } from "../lib/grpc-client";
import { GetDefinitionRequestSchema } from "../gen/proto/lemline/gateway/v1/gateway_pb";

export function useDefinition(namespace?: string, name?: string, version?: string) {
  return useQuery({
    queryKey: ["definition", namespace, name, version],
    enabled: Boolean(namespace && name),
    queryFn: async () => {
      const response = await gatewayClient.getDefinition(
        create(GetDefinitionRequestSchema, {
          namespace: namespace ?? "",
          name: name ?? "",
          version,
        }),
      );
      return response.definition;
    },
  });
}
