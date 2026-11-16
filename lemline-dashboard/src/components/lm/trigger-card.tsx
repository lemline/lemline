import type { WorkflowTrigger } from "../../lib/workflow-source";
import { BoltIcon, ClockIcon, PlayIcon } from "./icons";

/** "How this workflow starts", above the YAML of a definition. */
export function TriggerCard({ trigger }: { trigger: WorkflowTrigger }) {
  return (
    <section
      aria-label="How this workflow starts"
      className="flex w-full flex-wrap items-center gap-5 rounded-[14px] border border-lm-line bg-lm-panel p-5 font-sans text-lm-text shadow-lm-card"
    >
      <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-[10px] bg-lm-accent-soft text-lm-accent">
        {trigger.kind === "event" ? <BoltIcon /> : trigger.kind === "schedule" ? <ClockIcon /> : <PlayIcon width={22} height={22} />}
      </div>
      <div className="flex min-w-0 flex-[1_1_220px] flex-col gap-1">
        {trigger.kind === "event" && (
          <>
            <p className="m-0 text-[13px] font-semibold uppercase tracking-[0.06em] text-lm-muted">Starts on event</p>
            <p className="m-0 text-base font-semibold">
              {trigger.eventTypes.length > 1
                ? "A new instance starts each time these events arrive:"
                : "A new instance starts each time this event arrives:"}
            </p>
            {trigger.eventTypes.map((type) => (
              <p
                key={type}
                className="m-0 max-w-full self-start rounded-md bg-lm-subtle2 px-2 py-1 font-mono text-sm [overflow-wrap:anywhere]"
              >
                {type}
              </p>
            ))}
          </>
        )}
        {trigger.kind === "schedule" && (
          <>
            <p className="m-0 text-[13px] font-semibold uppercase tracking-[0.06em] text-lm-muted">Starts on a schedule</p>
            <p className="m-0 text-base font-semibold">{trigger.sentence}</p>
            {trigger.code && (
              <p className="m-0 max-w-full self-start rounded-md bg-lm-subtle2 px-2 py-1 font-mono text-sm [overflow-wrap:anywhere]">
                {trigger.code}
              </p>
            )}
          </>
        )}
        {trigger.kind === "manual" && <p className="m-0 text-base font-semibold">Starts on request</p>}
      </div>
    </section>
  );
}
