import { copyFileSync, existsSync, mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { dashboardProtoPath, gatewayProtoPath } from "./proto-paths.mjs";

if (!existsSync(gatewayProtoPath)) {
  console.error(`Gateway proto not found at ${gatewayProtoPath}`);
  process.exit(1);
}

mkdirSync(dirname(dashboardProtoPath), { recursive: true });
copyFileSync(gatewayProtoPath, dashboardProtoPath);

console.log(`Synced ${dashboardProtoPath} from ${gatewayProtoPath}`);
