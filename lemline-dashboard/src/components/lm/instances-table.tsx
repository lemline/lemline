import type { MouseEvent } from "react";
import { useViewportWidth } from "../../hooks/use-viewport-width";
import type { InstanceRow } from "../../lib/instance-row";
import { formatExact } from "../../lib/format";
import { cn } from "../../lib/utils";
import { StatusBadge } from "./status";

interface InstancesTableProps {
  rows: InstanceRow[];
  onOpen: (id: string) => void;
  onOpenDefinition: (ns: string, wf: string) => void;
}

const COLUMNS = "120px minmax(160px,1.3fr) 150px minmax(200px,1.7fr) 130px 90px";

function reasonColor(row: InstanceRow): string {
  if (row.status === "completed") return "text-lm-muted";
  return row.status === "faulted" ? "text-lm-fail-strong" : "text-lm-run";
}

export function InstancesTable({ rows, onOpen, onOpenDefinition }: InstancesTableProps) {
  const compact = useViewportWidth() < 1100;

  const stop = (event: MouseEvent, action: () => void) => {
    event.stopPropagation();
    action();
  };

  const workflowLink = (row: InstanceRow) => (
    <button
      type="button"
      onClick={(event) => stop(event, () => onOpenDefinition(row.ns, row.wf))}
      title={`Open the definition of ${row.wf}`}
      className="cursor-pointer border-0 bg-transparent p-0 text-left text-sm font-semibold text-lm-text underline decoration-lm-line-strong underline-offset-[3px] [overflow-wrap:anywhere] hover:decoration-lm-accent"
    >
      {row.wf}
    </button>
  );

  const idLink = (row: InstanceRow) => (
    <button
      type="button"
      onClick={(event) => stop(event, () => onOpen(row.id))}
      aria-label={`Open instance ${row.shortId} of ${row.wf}`}
      title={row.id}
      className="cursor-pointer border-0 bg-transparent p-0 font-mono text-[13px] text-lm-accent hover:underline"
    >
      {row.shortId}
    </button>
  );

  return (
    <div className="w-full overflow-x-auto rounded-[14px] border border-lm-line bg-lm-panel font-sans text-lm-text shadow-lm-card">
      {!compact ? (
        <div className="min-w-[900px]">
          <div
            className="grid gap-3 border-b-2 border-lm-line-strong bg-lm-panel px-5 py-3 text-[13px] font-semibold text-lm-muted"
            style={{ gridTemplateColumns: COLUMNS }}
          >
            <span>Status</span>
            <span>Workflow</span>
            <span>Instance</span>
            <span>Reason</span>
            <span>Started ↓</span>
            <span className="text-right">Duration</span>
          </div>
          {rows.map((row) => (
            <div
              key={row.id}
              onClick={() => onOpen(row.id)}
              title={`Open instance ${row.shortId} of ${row.wf}`}
              className="lm-row-hover grid w-full cursor-pointer items-center gap-3 border-b border-lm-line-soft bg-lm-panel px-5 py-3.5 text-left text-sm last:border-b-0"
              style={{ gridTemplateColumns: COLUMNS }}
            >
              <span>
                <StatusBadge status={row.status} />
              </span>
              <span className="flex flex-col items-start gap-0.5">
                {workflowLink(row)}
                <span className="text-[13px] text-lm-muted">
                  {row.ns} · v{row.ver}
                </span>
              </span>
              <span>{idLink(row)}</span>
              <span className="flex flex-col gap-0.5">
                <span className={cn(row.status === "completed" ? "font-normal" : "font-semibold", reasonColor(row))}>
                  {row.reason}
                </span>
                <span className="text-[13px] text-lm-muted">{row.reasonSub}</span>
              </span>
              <span className="flex flex-col gap-0.5" title={formatExact(row.startedExact)}>
                <span>{row.started}</span>
                <span className="text-[13px] text-lm-muted">{row.startedAt}</span>
              </span>
              <span className="text-right tabular-nums">{row.duration}</span>
            </div>
          ))}
        </div>
      ) : (
        <div className="flex flex-col">
          {rows.map((row) => (
            <div
              key={row.id}
              onClick={() => onOpen(row.id)}
              title={`Open instance ${row.shortId} of ${row.wf}`}
              className="lm-row-hover flex w-full cursor-pointer flex-col items-stretch gap-1.5 border-b border-lm-line-soft bg-lm-panel px-4 py-3.5 text-left text-sm last:border-b-0"
            >
              <span className="flex items-start justify-between gap-3">
                <span className="flex min-w-0 flex-col items-start gap-0.5">
                  {workflowLink(row)}
                  <span className="text-[13px] text-lm-muted">
                    {row.ns} · v{row.ver}
                  </span>
                </span>
                <StatusBadge status={row.status} />
              </span>
              {row.status !== "completed" && (
                <span className="flex flex-col gap-0.5">
                  <span className={cn("font-semibold", reasonColor(row))}>{row.reason}</span>
                  <span className="text-[13px] text-lm-muted">{row.reasonSub}</span>
                </span>
              )}
              <span className="flex flex-wrap gap-x-3 gap-y-1 text-[13px] text-lm-muted">
                <span title={formatExact(row.startedExact)}>
                  {row.started} · {row.startedAt}
                </span>
                <span>{row.duration !== "—" ? `took ${row.duration}` : "still running"}</span>
                {idLink(row)}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
