export function GanttSkeleton() {
  return (
    <div
      role="status"
      aria-label="Loading gantt timeline"
      className="space-y-2 rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-950"
    >
      {[...Array(6)].map((_, index) => (
        <div
          key={index}
          className="h-4 animate-pulse rounded bg-slate-200 dark:bg-slate-800"
          style={{ width: `${40 + index * 8}%` }}
        />
      ))}
    </div>
  );
}
