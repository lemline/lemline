import { useState } from "react";
import type { TimelineEventView } from "../../hooks/use-watch-workflow-projection";
import { useViewportWidth } from "../../hooks/use-viewport-width";
import { toEventRows } from "../../lib/event-rows";
import { cn } from "../../lib/utils";
import { CodeBlock } from "./code-block";
import { Drawer, DrawerBody, DrawerHeader } from "./drawer";

export function EventsTable({ events, startMs }: { events: TimelineEventView[]; startMs: number }) {
  const narrow = useViewportWidth() < 720;
  const [open, setOpen] = useState<string | null>(null);
  const rows = toEventRows(events, startMs);
  const current = rows.find((row) => row.key === open);
  const columns = narrow ? "128px 140px minmax(0,1fr)" : "170px 240px minmax(0,1fr)";
  const padX = narrow ? 16 : 24;

  return (
    <>
      <section
        aria-label="Events"
        className="w-full overflow-x-auto rounded-[14px] border border-lm-line bg-lm-panel font-sans text-lm-text shadow-lm-card"
      >
        <div style={{ minWidth: narrow ? 0 : 680 }}>
          {!narrow && (
            <div
              className="grid gap-2.5 border-b-2 border-lm-line-strong bg-lm-panel py-2.5 text-[13px] font-semibold text-lm-muted"
              style={{ gridTemplateColumns: columns, paddingLeft: padX, paddingRight: padX }}
            >
              <span>At</span>
              <span>Type</span>
              <span>Task</span>
            </div>
          )}
          {rows.length === 0 && <p className="m-0 px-6 py-5 text-sm text-lm-muted">No event yet.</p>}
          {rows.map((row) =>
            narrow ? (
              <button
                key={row.key}
                type="button"
                aria-haspopup="dialog"
                onClick={() => setOpen(row.key)}
                className={cn(
                  "lm-row-hover flex w-full cursor-pointer flex-col items-start gap-1 border-0 border-b border-lm-line-soft px-4 py-3 text-left text-lm-text last:border-b-0",
                  open === row.key ? "bg-lm-accent-soft" : "bg-transparent",
                )}
              >
                <span className={cn("font-mono text-[13px] [overflow-wrap:anywhere]", row.isError ? "text-lm-fail" : "text-lm-text2")}>
                  {row.shortType}
                </span>
                <span className="flex flex-wrap gap-x-2.5 gap-y-1 text-[13px] text-lm-muted">
                  <span className="tabular-nums">{row.clock}</span>
                  <span className={cn("font-mono", row.task === "Workflow" ? "text-lm-muted" : "text-lm-text")}>{row.task}</span>
                </span>
              </button>
            ) : (
              <button
                key={row.key}
                type="button"
                aria-haspopup="dialog"
                onClick={() => setOpen(row.key)}
                className={cn(
                  "lm-row-hover grid w-full cursor-pointer items-center gap-2.5 border-0 border-b border-lm-line-soft py-3 text-left text-sm text-lm-text last:border-b-0",
                  open === row.key ? "bg-lm-accent-soft" : "bg-transparent",
                )}
                style={{ gridTemplateColumns: columns, paddingLeft: padX, paddingRight: padX }}
              >
                <span className="whitespace-nowrap tabular-nums">{row.clock}</span>
                <span className={cn("font-mono text-[13px] [overflow-wrap:anywhere]", row.isError ? "text-lm-fail" : "text-lm-text2")}>
                  {row.shortType}
                </span>
                <span
                  className={cn(
                    "font-mono text-[13px] [overflow-wrap:anywhere]",
                    row.task === "Workflow" ? "text-lm-muted" : "text-lm-text",
                  )}
                >
                  {row.task}
                </span>
              </button>
            ),
          )}
        </div>
      </section>
      {current && (
        <Drawer label="Event details" closeLabel="Close the event panel" onClose={() => setOpen(null)}>
          {(close) => (
            <>
              <DrawerHeader
                onClose={close}
                title={<h2 className="m-0 text-[22px] font-bold [overflow-wrap:anywhere]">{current.what}</h2>}
                subtitle={
                  <p className="m-0 -mt-0.5 flex flex-wrap items-center gap-2 text-[15px] text-lm-muted">
                    <span className="tabular-nums">{current.clock}</span>
                    <span aria-hidden="true">·</span>
                    <span className={current.isError ? "font-semibold text-lm-fail" : "font-normal text-lm-muted"}>
                      {current.isError ? "Error" : "Lifecycle"}
                    </span>
                  </p>
                }
              />
              <DrawerBody>
                <dl className="m-0 grid grid-cols-[max-content_minmax(0,1fr)] gap-x-5 gap-y-2.5 text-sm">
                  <dt className="text-lm-muted">Type</dt>
                  <dd className="m-0 font-mono text-[13px] [overflow-wrap:anywhere]">{current.type}</dd>
                  <dt className="text-lm-muted">Task</dt>
                  <dd className="m-0 font-mono text-[13px]">{current.task}</dd>
                  <dt className="text-lm-muted">Time</dt>
                  <dd className="m-0 tabular-nums">{current.time}</dd>
                </dl>
                <CodeBlock text={current.json} />
              </DrawerBody>
            </>
          )}
        </Drawer>
      )}
    </>
  );
}
