import { existsSync, readFileSync } from "node:fs";
import { dashboardProtoPath, gatewayProtoPath } from "./proto-paths.mjs";

if (!existsSync(gatewayProtoPath)) {
  console.error(`Gateway proto not found at ${gatewayProtoPath}`);
  process.exit(1);
}

if (!existsSync(dashboardProtoPath)) {
  console.error(`Dashboard proto not found at ${dashboardProtoPath}`);
  console.error("Run: npm run proto:sync");
  process.exit(1);
}

const gatewayProto = readFileSync(gatewayProtoPath);
const dashboardProto = readFileSync(dashboardProtoPath);

if (!gatewayProto.equals(dashboardProto)) {
  console.error("Proto drift detected between gateway and dashboard.");
  console.error(`Canonical: ${gatewayProtoPath}`);
  console.error(`Dashboard: ${dashboardProtoPath}`);
  console.error("Run: npm run proto:sync");
  process.exit(1);
}

console.log("Dashboard proto is in sync with gateway proto.");
