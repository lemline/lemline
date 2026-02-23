import { cn } from "../../lib/utils";

interface LiveIndicatorProps {
  live: boolean;
}

export function LiveIndicator({ live }: LiveIndicatorProps) {
  return (
    <div className="inline-flex items-center gap-2 text-sm">
      <span
        className={cn(
          "h-2.5 w-2.5 rounded-full",
          live ? "animate-pulse bg-emerald-500" : "bg-slate-400",
        )}
      />
      <span className={live ? "text-emerald-600 dark:text-emerald-300" : "text-slate-500"}>
        {live ? "Live" : "Disconnected"}
      </span>
    </div>
  );
}
