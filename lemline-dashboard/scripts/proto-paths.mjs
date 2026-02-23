import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const dashboardDir = resolve(scriptDir, "..");

export const gatewayProtoPath = resolve(
  dashboardDir,
  "../lemline-runner-gateway/src/main/proto/lemline/gateway/v1/workflow_gateway.proto",
);

export const dashboardProtoPath = resolve(
  dashboardDir,
  "proto/lemline/gateway/v1/workflow_gateway.proto",
);
