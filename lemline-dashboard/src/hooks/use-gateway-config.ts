import { create } from "@bufbuild/protobuf";
import { useQuery } from "@tanstack/react-query";
import { GetGatewayConfigRequestSchema } from "../gen/proto/lemline/gateway/v1/gateway_pb";
import { gatewayClient } from "../lib/grpc-client";

/** The configuration the gateway runs with. It only changes when the gateway restarts. */
export function useGatewayConfig() {
  return useQuery({
    queryKey: ["gateway-config"],
    queryFn: () => gatewayClient.getGatewayConfig(create(GetGatewayConfigRequestSchema, {})),
    staleTime: 60_000,
    retry: false,
  });
}
