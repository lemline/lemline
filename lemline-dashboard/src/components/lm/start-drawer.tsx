import { create } from "@bufbuild/protobuf";
import { ConnectError } from "@connectrpc/connect";
import { useState } from "react";
import { uuidv7 } from "uuidv7";
import { StartInstanceRequestSchema } from "../../gen/proto/lemline/gateway/v1/gateway_pb";
import { gatewayClient } from "../../lib/grpc-client";
import { formatExact } from "../../lib/format";
import { timestampToMilliseconds, valueFromJsonText } from "../../lib/proto";
import { exampleInput, type WorkflowTrigger } from "../../lib/workflow-source";
import { cn } from "../../lib/utils";
import { Drawer, DrawerBody, DrawerHeader } from "./drawer";
import { useDrawerPadding } from "../../hooks/use-layout";
import { PlayIcon, WarningIcon } from "./icons";

interface StartDrawerProps {
  ns: string;
  wf: string;
  version: string;
  trigger: WorkflowTrigger;
  onClose: () => void;
  /** Called with the ID of the instance created. */
  onStarted: (instanceId: string) => void;
  onSeeInstances: () => void;
}

export function StartDrawer({ ns, wf, version, trigger, onClose, onStarted, onSeeInstances }: StartDrawerProps) {
  const example = exampleInput(trigger);
  const [text, setText] = useState(example);
  const [advanced, setAdvanced] = useState(false);
  const [instanceId, setInstanceId] = useState(() => uuidv7());
  const [zoneId, setZoneId] = useState(() => Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC");
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [scheduled, setScheduled] = useState<{ nextRunAt?: number } | null>(null);
  const pad = useDrawerPadding();
  // The runtime refuses to start by hand a workflow that starts on an event.
  const eventOnly = trigger.kind === "event";

  let valid = true;
  try {
    JSON.parse(text);
  } catch {
    valid = false;
  }

  const start = async () => {
    if (!valid || starting || eventOnly) return;
    setStarting(true);
    setError(null);
    try {
      const response = await gatewayClient.startInstance(
        create(StartInstanceRequestSchema, {
          namespace: ns,
          name: wf,
          version,
          instanceId: instanceId.trim() || undefined,
          input: valueFromJsonText(text),
          zoneId: trigger.kind === "schedule" ? zoneId : undefined,
        }),
      );
      if (response.scheduleOnly) {
        const next = timestampToMilliseconds(response.nextRunAt);
        setScheduled({ nextRunAt: next != null && next > Date.now() ? next : undefined });
        return;
      }
      onStarted(response.instanceId);
    } catch (cause) {
      setError(ConnectError.from(cause).rawMessage || "The instance could not be started.");
      setInstanceId(uuidv7());
    } finally {
      setStarting(false);
    }
  };

  return (
    <Drawer label="Start an instance" closeLabel="Close the start panel" onClose={onClose}>
      {(close) => (
        <div className="flex min-h-full flex-col">
          <DrawerHeader
            onClose={close}
            title={<h2 className="m-0 text-[22px] font-bold">Start an instance</h2>}
            subtitle={
              <p className="m-0 -mt-1 text-[15px] text-lm-muted">
                {ns} / {wf} · v{version}
              </p>
            }
          />
          <DrawerBody>
            {trigger.kind === "event" && (
              <div className="flex gap-3 rounded-[10px] border border-lm-warn-line bg-lm-warn-soft px-4 py-3.5">
                <WarningIcon className="mt-px shrink-0 text-lm-warn" />
                <div className="flex flex-col gap-1">
                  <p className="m-0 text-[15px] font-semibold text-lm-warn">This workflow normally starts on an event</p>
                  <p className="m-0 text-sm leading-normal text-lm-warn">
                    Lemline starts it each time {trigger.eventTypes.join(" or ")} arrives, and does not start it by hand.
                    Send that event instead: the example below shows the input the workflow then receives.
                  </p>
                </div>
              </div>
            )}
            {trigger.kind === "schedule" && (
              <div className="flex gap-3 rounded-[10px] border border-lm-warn-line bg-lm-warn-soft px-4 py-3.5">
                <WarningIcon className="mt-px shrink-0 text-lm-warn" />
                <div className="flex flex-col gap-1">
                  <p className="m-0 text-[15px] font-semibold text-lm-warn">This workflow runs on a schedule</p>
                  <p className="m-0 text-sm leading-normal text-lm-warn">
                    Starting it registers its schedule: the first instance starts at the next planned time.
                  </p>
                </div>
              </div>
            )}

            <div className="flex flex-col gap-2">
              <div className="flex items-baseline justify-between gap-2">
                <label htmlFor="start-input" className="text-[15px] font-semibold">
                  Input
                </label>
{!eventOnly && (
                <button
                  type="button"
                  onClick={() => setText(example)}
                  className="min-h-8 cursor-pointer border-0 bg-transparent px-2 text-sm font-medium text-lm-accent"
                >
                  Reset to example
                </button>
                )}
              </div>
              <textarea
                id="start-input"
                rows={9}
                spellCheck={false}
                value={text}
                onChange={(event) => setText(event.target.value)}
                readOnly={eventOnly}
                className={cn(
                  "w-full resize-y rounded-[10px] border bg-lm-subtle px-4 py-3.5 font-mono text-sm leading-[1.6] text-lm-text",
                  valid ? "border-lm-line-strong" : "border-lm-fail-bar",
                )}
              />
              <p role="status" className={cn("m-0 flex items-center gap-1.5 text-sm", valid ? "text-lm-done" : "text-lm-fail")}>
                {valid ? "✓ Valid JSON" : "✕ Not valid JSON yet"}
              </p>
            </div>

            <div className="rounded-[10px] border border-lm-line">
              <button
                type="button"
                aria-expanded={advanced}
                onClick={() => setAdvanced((previous) => !previous)}
                className="flex min-h-12 w-full cursor-pointer items-center justify-between border-0 bg-transparent px-4 text-[15px] font-semibold text-lm-text"
              >
                Advanced options<span aria-hidden="true">{advanced ? "−" : "+"}</span>
              </button>
              {advanced && (
                <div className="flex flex-col gap-3.5 px-4 pb-4">
                  <div className="flex flex-col gap-1.5">
                    <label htmlFor="start-id" className="text-sm font-semibold">
                      Instance ID
                    </label>
                    <input
                      id="start-id"
                      value={instanceId}
                      onChange={(event) => setInstanceId(event.target.value)}
                      className="min-h-11 rounded-lg border border-lm-line-strong bg-lm-panel px-3 font-mono text-[13px] text-lm-text"
                    />
                    <p className="m-0 text-[13px] text-lm-muted">
                      Generated for you. Set your own only to make the start idempotent.
                    </p>
                  </div>
                  {trigger.kind === "schedule" && (
                    <div className="flex flex-col gap-1.5">
                      <label htmlFor="start-zone" className="text-sm font-semibold">
                        Time zone
                      </label>
                      <input
                        id="start-zone"
                        value={zoneId}
                        onChange={(event) => setZoneId(event.target.value)}
                        className="min-h-11 rounded-lg border border-lm-line-strong bg-lm-panel px-3 font-mono text-[13px] text-lm-text"
                      />
                      <p className="m-0 text-[13px] text-lm-muted">Used to read the schedule, for example Europe/Paris.</p>
                    </div>
                  )}
                </div>
              )}
            </div>

            {error && (
              <div role="alert" className="rounded-[10px] border border-lm-fail-line bg-lm-fail-soft px-4 py-3.5">
                <p className="m-0 text-[15px] font-semibold text-lm-fail-strong">Not started</p>
                <p className="m-0 mt-1 text-sm leading-normal text-lm-fail-strong [overflow-wrap:anywhere]">{error}</p>
              </div>
            )}
            {scheduled && (
              <div role="status" className="flex flex-col items-start gap-2 rounded-[10px] border border-lm-done-line bg-lm-done-soft px-4 py-3.5">
                <p className="m-0 text-[15px] font-semibold text-lm-done-strong">Schedule registered</p>
                <p className="m-0 text-sm text-lm-done-strong">
                  {scheduled.nextRunAt
                    ? `The next instance starts ${formatExact(scheduled.nextRunAt)}.`
                    : "Instances start at the planned times."}
                </p>
                <button
                  type="button"
                  onClick={onSeeInstances}
                  className="min-h-8 cursor-pointer border-0 bg-transparent p-0 text-sm font-semibold text-lm-accent"
                >
                  See the instances of {wf}
                </button>
              </div>
            )}
          </DrawerBody>
          <footer
            className="flex flex-wrap items-center justify-between gap-3 border-t border-lm-line bg-lm-subtle"
            style={{ padding: pad.foot }}
          >
            <p className="m-0 text-sm text-lm-muted">
              {eventOnly
                ? "Starts when the event arrives."
                : trigger.kind === "schedule"
                  ? "The schedule starts the instances."
                  : "You'll be taken to the new instance."}
            </p>
            <div className="flex gap-2">
              <button
                type="button"
                onClick={() => void start()}
                disabled={!valid || starting || eventOnly}
                title={eventOnly ? "Lemline starts this workflow only when its event arrives" : undefined}
                className={cn(
                  "flex min-h-11 cursor-pointer items-center gap-2 rounded-lg border-0 px-[18px] text-[15px] font-semibold text-lm-panel",
                  valid && !starting && !eventOnly ? "bg-lm-primary-bg" : "bg-lm-line-strong",
                )}
              >
                <PlayIcon />
                {starting ? "Starting…" : "Start instance"}
              </button>
            </div>
          </footer>
        </div>
      )}
    </Drawer>
  );
}
