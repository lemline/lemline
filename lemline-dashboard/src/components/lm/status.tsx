import { STATUS_LABEL, type UiStatus } from "../../lib/status";
import { cn } from "../../lib/utils";
import { StatusDot } from "./icons";

const BADGE_CLASS: Record<UiStatus, string> = {
  running: "bg-lm-run-soft text-lm-run",
  faulted: "bg-lm-fail-soft text-lm-fail",
  completed: "bg-lm-done-soft text-lm-done",
};

export function StatusBadge({ status, className }: { status: UiStatus; className?: string }) {
  return (
    <span
      className={cn(
        "inline-flex shrink-0 items-center gap-1.5 rounded-full px-2.5 py-[3px] text-[13px] font-semibold",
        BADGE_CLASS[status],
        className,
      )}
    >
      <StatusDot />
      {STATUS_LABEL[status]}
    </span>
  );
}
