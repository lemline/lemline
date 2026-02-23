import { Card } from "../ui/card";

export function EventLogSkeleton() {
  return (
    <Card role="status" aria-label="Loading event log" className="overflow-hidden p-0">
      <div className="grid grid-cols-[1.2fr_1.2fr_0.9fr_0.7fr] border-b border-slate-200 bg-slate-50 px-4 py-2 dark:border-slate-800 dark:bg-slate-900">
        <span className="h-3.5 w-16 animate-pulse rounded bg-slate-200 dark:bg-slate-800" />
        <span className="h-3.5 w-20 animate-pulse rounded bg-slate-200 dark:bg-slate-800" />
        <span className="h-3.5 w-12 animate-pulse rounded bg-slate-200 dark:bg-slate-800" />
        <span className="h-3.5 w-14 animate-pulse rounded bg-slate-200 dark:bg-slate-800" />
      </div>
      <div className="divide-y divide-slate-200 dark:divide-slate-800">
        {Array.from({ length: 8 }, (_, index) => (
          <div key={index} className="grid grid-cols-[1.2fr_1.2fr_0.9fr_0.7fr] items-center gap-3 px-4 py-3">
            <span className="h-3.5 w-28 animate-pulse rounded bg-slate-200 dark:bg-slate-800" />
            <span className="h-3.5 w-36 animate-pulse rounded bg-slate-200 dark:bg-slate-800" />
            <span className="h-3.5 w-20 animate-pulse rounded bg-slate-200 dark:bg-slate-800" />
            <span className="h-3.5 w-14 animate-pulse rounded bg-slate-200 dark:bg-slate-800" />
          </div>
        ))}
      </div>
    </Card>
  );
}
