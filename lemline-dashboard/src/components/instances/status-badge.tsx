import { InstanceStatus } from "../../gen/proto/lemline/gateway/v1/gateway_pb";
import { Badge } from "../ui/badge";

interface StatusBadgeProps {
  status: InstanceStatus | string;
}

export function StatusBadge({ status }: StatusBadgeProps) {
  const normalized = normalizeStatus(status);
  if (normalized === "RUNNING") return <Badge tone="running">Running</Badge>;
  if (normalized === "COMPLETED") return <Badge tone="completed">Completed</Badge>;
  if (normalized === "FAULTED") return <Badge tone="faulted">Faulted</Badge>;
  if (normalized === "PENDING") return <Badge tone="pending">Pending</Badge>;
  return <Badge>{normalized || "Unknown"}</Badge>;
}

function normalizeStatus(status: InstanceStatus | string): string {
  if (typeof status === "string") {
    return status.toUpperCase();
  }

  switch (status) {
    case InstanceStatus.PENDING:
      return "PENDING";
    case InstanceStatus.RUNNING:
      return "RUNNING";
    case InstanceStatus.COMPLETED:
      return "COMPLETED";
    case InstanceStatus.FAULTED:
      return "FAULTED";
    default:
      return "UNKNOWN";
  }
}
