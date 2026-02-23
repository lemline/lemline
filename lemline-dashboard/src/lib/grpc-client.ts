import { createPromiseClient } from "@connectrpc/connect";
import { createGrpcWebTransport } from "@connectrpc/connect-web";
import { WorkflowGateway } from "../gen/proto/lemline/gateway/v1/workflow_gateway_connect";
import { getRuntimeConfig } from "./runtime-config";

const runtime = getRuntimeConfig();

const transport = createGrpcWebTransport({
  baseUrl: runtime.gatewayBaseUrl,
  useBinaryFormat: true,
});

export const gatewayClient = createPromiseClient(WorkflowGateway, transport);
