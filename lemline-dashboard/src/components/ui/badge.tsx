import { cn } from "../../lib/utils";

interface BadgeProps extends React.HTMLAttributes<HTMLSpanElement> {
  tone?: "default" | "running" | "completed" | "faulted" | "pending";
}

const toneStyles: Record<NonNullable<BadgeProps["tone"]>, string> = {
  default: "bg-slate-100 text-slate-800 dark:bg-slate-800 dark:text-slate-100",
  running: "bg-blue-100 text-blue-800 dark:bg-blue-900/40 dark:text-blue-200",
  completed: "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-200",
  faulted: "bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-200",
  pending: "bg-slate-200 text-slate-700 dark:bg-slate-700 dark:text-slate-200",
};

export function Badge({ className, tone = "default", ...props }: BadgeProps) {
  return (
    <span
      className={cn(
        "inline-flex items-center rounded-full px-2.5 py-1 text-xs font-semibold uppercase tracking-wide",
        toneStyles[tone],
        className,
      )}
      {...props}
    />
  );
}
