import { useState } from "react";
import { useViewportWidth } from "../../hooks/use-viewport-width";
import { formatSpan } from "../../lib/format";
import { readableError } from "../../lib/errors";
import { jsonText, type Segment, type StepRow, type StepState, type TimelineModel } from "../../lib/timeline";
import { cn } from "../../lib/utils";
import { CodeBlock } from "./code-block";
import { Drawer, DrawerBody, DrawerHeader } from "./drawer";

const SEGMENT_STYLE: Record<Segment["kind"], string> = {
  ran: "bg-[var(--lm-ran)]",
  running: "bg-lm-run-bar",
  failed: "bg-lm-fail-bar",
  backoff: "border-[1.5px] border-dashed border-lm-muted bg-transparent",
  waiting: "lm-waiting",
  scheduled: "border-[1.5px] border-dashed border-lm-line-strong bg-transparent",
  interrupted: "bg-lm-line-strong",
  group: "bg-[var(--lm-group)]",
};

const STATE_PILL: Record<StepState, { label: string; className: string }> = {
  completed: { label: "Completed", className: "border-lm-done-line bg-lm-done-soft text-lm-done-strong" },
  running: { label: "Running", className: "border-lm-run-line bg-lm-run-soft text-lm-run-strong" },
  waiting: { label: "Waiting for an event", className: "border-lm-warn-line bg-lm-warn-soft text-lm-warn" },
  retrying: { label: "Waiting before retry", className: "border-lm-warn-line bg-lm-warn-soft text-lm-warn" },
  failed: { label: "Failed", className: "border-lm-fail-line bg-lm-fail-soft text-lm-fail-strong" },
  scheduled: { label: "Waiting to start", className: "border-lm-line-strong bg-lm-subtle text-lm-text2" },
  interrupted: { label: "Interrupted", className: "border-lm-line-strong bg-lm-subtle text-lm-text2" },
};

function Legend() {
  const item = "flex items-center gap-2";
  const swatch = "h-2.5 w-[22px] rounded-[3px]";
  return (
    <ul aria-label="Legend" className="m-0 flex list-none flex-wrap gap-[18px] p-0 text-sm text-lm-text2">
      <li className={item}>
        <span className={cn(swatch, SEGMENT_STYLE.ran)} />
        Ran
      </li>
      <li className={item}>
        <span className={cn(swatch, SEGMENT_STYLE.running)} />
        Running
      </li>
      <li className={item}>
        <span className={cn(swatch, SEGMENT_STYLE.failed)} />
        Failed
      </li>
      <li className={item}>
        <span className={cn(swatch, SEGMENT_STYLE.backoff)} />
        Waiting before retry
      </li>
      <li className={item}>
        <span className={cn(swatch, SEGMENT_STYLE.waiting)} />
        Waiting for an event or a delay
      </li>
      <li className={item}>
        <span className={cn(swatch, SEGMENT_STYLE.scheduled)} />
        Waiting to start
      </li>
      <li className={item}>
        <span className={cn(swatch, SEGMENT_STYLE.interrupted)} />
        Interrupted
      </li>
    </ul>
  );
}

interface GanttProps {
  model: TimelineModel;
  nowMs: number;
}

export function Gantt({ model, nowMs }: GanttProps) {
  const width = useViewportWidth();
  const [selected, setSelected] = useState<string | null>(null);
  const narrow = width < 720;
  const labelWidth = narrow ? 128 : width < 1100 ? 180 : 240;
  const minWidth = narrow ? 520 : width < 1100 ? 660 : 900;
  const available = width - (width >= 900 ? 240 : 0) - (narrow ? 32 : 80) - 2;
  const columns = `${labelWidth}px minmax(0,1fr)`;
  const current = model.rows.find((row) => row.id === selected);
  // Keep tick labels apart on the width the axis really has.
  const axisPx = Math.max(minWidth, available) - labelWidth - 40;
  const ticks: typeof model.ticks = [];
  const last = model.ticks.length - 1;
  const byPriority = model.ticks
    .map((tick, index) => ({ tick, rank: tick.strong ? 0 : index === 0 || index === last ? 1 : 2 }))
    .sort((left, right) => left.rank - right.rank)
    .map((item) => item.tick);
  for (const tick of byPriority) {
    const room = (tick.strong ? 120 : 64) / axisPx * 100;
    if (ticks.every((kept) => Math.abs(kept.pct - tick.pct) >= Math.max(room, kept.strong ? (120 / axisPx) * 100 : 0))) {
      ticks.push(tick);
    }
  }

  if (model.rows.length === 0) {
    return (
      <div className="flex flex-col gap-4">
        <Legend />
        <p className="m-0 rounded-[14px] border border-dashed border-lm-line-strong bg-lm-panel px-6 py-7 text-sm text-lm-muted">
          No task has started yet.
        </p>
      </div>
    );
  }

  return (
    <div className="flex w-full flex-col gap-4 font-sans text-lm-text">
      <Legend />
      <section aria-label="Gantt chart" className="overflow-x-auto rounded-[14px] border border-lm-line bg-lm-panel shadow-lm-card">
        <div style={{ minWidth }}>
          <div className="grid border-b-2 border-lm-line-strong bg-lm-panel" style={{ gridTemplateColumns: columns }}>
            <div className="px-4 py-2.5 text-[13px] font-semibold text-lm-muted">Step</div>
            <div className="relative mr-10 h-[38px] text-xs tabular-nums text-lm-muted">
              {[...ticks].sort((left, right) => left.pct - right.pct).map((tick) => (
                <span
                  key={`${tick.label}-${tick.pct}`}
                  className={cn("absolute top-3 whitespace-nowrap", tick.strong && "font-semibold")}
                  style={{ left: `${tick.pct}%` }}
                >
                  {tick.label}
                </span>
              ))}
            </div>
          </div>
          {model.rows.map((row) => {
            const failedOnly = row.state === "failed" && !row.isGroup;
            const left = model.toPct(row.start);
            return (
              <button
                key={row.id}
                type="button"
                aria-haspopup="dialog"
                onClick={() => setSelected(row.id)}
                className={cn(
                  "lm-row-hover grid w-full cursor-pointer border-0 border-b border-lm-line-soft p-0 text-left text-lm-text last:border-b-0",
                  selected === row.id ? "bg-lm-accent-soft" : "bg-lm-panel",
                )}
                style={{ gridTemplateColumns: columns }}
              >
                <span
                  className="box-border flex min-h-[54px] min-w-0 flex-col gap-0.5 py-2.5 pr-4"
                  style={{ paddingLeft: 16 + row.indent * 20 }}
                >
                  <span className="truncate text-sm font-semibold" title={row.label}>
                    {row.label}
                  </span>
                  <span className="truncate text-xs text-lm-muted" title={row.typeLabel}>
                    {row.typeLabel}
                  </span>
                </span>
                <span className="relative mr-10 block h-[54px]">
                  {row.segments.map((segment, index) => {
                    const start = model.toPct(segment.start);
                    const end = model.toPct(segment.end);
                    return (
                      <span
                        key={index}
                        className={cn("absolute top-4 box-border block h-4 min-w-1 rounded-[3px]", SEGMENT_STYLE[segment.kind])}
                        style={{ left: `${start}%`, width: `${Math.max(0, end - start)}%` }}
                      />
                    );
                  })}
                  <span
                    className={cn("absolute top-[35px] whitespace-nowrap text-xs", failedOnly ? "text-lm-fail" : "text-lm-muted")}
                    style={{ left: `${left}%` }}
                  >
                    {row.note}
                  </span>
                </span>
              </button>
            );
          })}
        </div>
      </section>
      {available < minWidth && <p className="m-0 text-[13px] text-lm-muted">Scroll sideways to see the whole timeline.</p>}
      {current && <TaskDrawer row={current} t0={model.t0} nowMs={nowMs} onClose={() => setSelected(null)} />}
    </div>
  );
}

function TaskDrawer({ row, t0, nowMs, onClose }: { row: StepRow; t0: number; nowMs: number; onClose: () => void }) {
  const pill =
    row.state === "waiting" && row.listensTo.length === 0 ? { ...STATE_PILL.waiting, label: "Waiting" } : STATE_PILL[row.state];
  const live = row.end === undefined;
  const duration = formatSpan((row.end ?? nowMs) - row.start) + (live ? " so far" : "");
  const blocks: { title: string; json: string; error?: boolean }[] = [];
  if (row.input !== undefined) blocks.push({ title: "Input", json: jsonText(row.input) });
  if (row.state === "failed" && row.error) blocks.push({ title: "Error", json: jsonText(row.error), error: true });
  else if (row.hasOutput) blocks.push({ title: "Output", json: jsonText(row.output) });

  return (
    <Drawer label="Task details" closeLabel="Close the task panel" onClose={onClose}>
      {(close) => (
        <>
          <DrawerHeader
            onClose={close}
            title={<h2 className="m-0 font-mono text-xl font-semibold [overflow-wrap:anywhere]">{row.label}</h2>}
            subtitle={
              <p className="m-0 flex flex-wrap items-center gap-2.5 text-[15px] text-lm-muted">
                <span className={cn("inline-flex min-h-[26px] items-center rounded-full border px-2.5 text-[13px] font-semibold", pill.className)}>
                  {pill.label}
                </span>
                <span>{row.typeLabel}</span>
              </p>
            }
          />
          <DrawerBody>
            <p className="m-0 text-[15px] leading-[1.55] text-lm-text2">{row.detail}</p>
            <dl className="m-0 grid grid-cols-[max-content_minmax(0,1fr)] gap-x-5 gap-y-2.5 text-sm">
              <dt className="text-lm-muted">Position</dt>
              <dd className="m-0 font-mono text-[13px] [overflow-wrap:anywhere]">{row.pointer}</dd>
              <dt className="text-lm-muted">Started</dt>
              <dd className="m-0 tabular-nums">+{formatSpan(Math.max(0, row.start - t0))} after the instance started</dd>
              <dt className="text-lm-muted">Duration</dt>
              <dd className="m-0 tabular-nums">{duration}</dd>
            </dl>
            {row.attempts.length > 1 && (
              <section className="flex flex-col gap-2.5">
                <h3 className="m-0 text-[15px] font-bold">Attempts</h3>
                <ol className="m-0 list-none overflow-hidden rounded-[10px] border border-lm-line p-0">
                  {row.attempts.map((attempt, index) => {
                    const error = attempt.error ? readableError(attempt.error).headline : "";
                    const span = attempt.end !== undefined ? ` · ${formatSpan(attempt.end - attempt.start)}` : "";
                    const text =
                      attempt.outcome === "failed"
                        ? `Failed${error ? ` · ${error}` : ""}${span}`
                        : attempt.outcome === "running"
                          ? "Running"
                          : attempt.outcome === "interrupted"
                            ? `Interrupted${span}`
                            : `Succeeded${span}`;
                    return (
                      <li
                        key={index}
                        className="grid grid-cols-[90px_minmax(0,1fr)] gap-3 border-b border-lm-line-soft px-3.5 py-2.5 text-sm last:border-b-0"
                      >
                        <span className="text-lm-muted">Attempt {index + 1}</span>
                        <span className={attempt.outcome === "failed" ? "text-lm-fail" : "text-lm-text"}>{text}</span>
                      </li>
                    );
                  })}
                </ol>
              </section>
            )}
            {blocks.map((block) => (
              <section key={block.title} className="flex flex-col gap-2.5">
                <h3 className={cn("m-0 text-[15px] font-bold", block.error ? "text-lm-fail" : "text-lm-text")}>{block.title}</h3>
                <CodeBlock text={block.json} />
              </section>
            ))}
            {live && <p className="m-0 text-sm text-lm-muted">No output yet: the task has not finished.</p>}
          </DrawerBody>
        </>
      )}
    </Drawer>
  );
}
