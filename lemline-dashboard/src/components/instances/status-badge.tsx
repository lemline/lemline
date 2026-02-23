import { Badge } from "../ui/badge";

interface StatusBadgeProps {
  status: string;
}

export function StatusBadge({ status }: StatusBadgeProps) {
  const normalized = status.toUpperCase();
  if (normalized === "RUNNING") return <Badge tone="running">Running</Badge>;
  if (normalized === "COMPLETED") return <Badge tone="completed">Completed</Badge>;
  if (normalized === "FAULTED") return <Badge tone="faulted">Faulted</Badge>;
  if (normalized === "PENDING") return <Badge tone="pending">Pending</Badge>;
  return <Badge>{status}</Badge>;
}
