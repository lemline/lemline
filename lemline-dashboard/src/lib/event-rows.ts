import type { TimelineEventView } from "../hooks/use-watch-workflow-projection";
import { readableError } from "./errors";
import { formatEventClock, formatSpan, formatUtc } from "./format";
import { cloudEventOf } from "./timeline";
import { taskNamesOf } from "./workflow-source";

export interface EventRow {
  key: string;
  clock: string;
  type: string;
  shortType: string;
  task: string;
  what: string;
  isError: boolean;
  time: string;
  json: string;
}

function describe(event: TimelineEventView, task: string): { what: string; isError: boolean } {
  const type = event.eventType;
  const data = (cloudEventOf(event)?.data ?? {}) as Record<string, unknown>;
  const error = data.error && typeof data.error === "object" ? readableError(data.error as Record<string, never>).headline : "";
  if (type.includes("workflow.created")) return { what: "Instance created", isError: false };
  if (type.includes("workflow.started")) return { what: "Instance started", isError: false };
  if (type.includes("workflow.completed")) return { what: "Instance completed", isError: false };
  if (type.includes("workflow.faulted")) return { what: `Instance faulted${error ? `: ${error}` : ""}`, isError: true };
  if (type.includes("task.created")) return { what: `${task} scheduled`, isError: false };
  if (type.includes("task.started")) return { what: `${task} started`, isError: false };
  if (type.includes("task.completed")) return { what: `${task} completed`, isError: false };
  if (type.includes("task.faulted")) return { what: `${task} failed${error ? `: ${error}` : ""}`, isError: true };
  if (type.includes("task.retried")) {
    const count = typeof data.retryCount === "number" ? ` (retry ${data.retryCount})` : "";
    return { what: `${task} retried${count}`, isError: false };
  }
  return { what: type, isError: false };
}

export function toEventRows(events: TimelineEventView[], startMs: number): EventRow[] {
  return events.map((event, index) => {
    const names = event.taskPointer ? taskNamesOf(event.taskPointer) : [];
    const task = names[names.length - 1] ?? "Workflow";
    const { what, isError } = describe(event, task);
    const timeMs = Date.parse(event.eventTime);
    const offset = Number.isFinite(timeMs) ? ` · +${formatSpan(Math.max(0, timeMs - startMs))} after the start` : "";
    let json = event.payloadJson;
    try {
      json = JSON.stringify(JSON.parse(event.payloadJson), null, 2);
    } catch {
      // Keep the raw text when it is not JSON.
    }
    return {
      key: `${String(event.sequence)}-${index}`,
      clock: formatEventClock(event.eventTime),
      type: event.eventType,
      shortType: event.eventType.replace(/^io\.serverlessworkflow\./, ""),
      task,
      what,
      isError: isError || event.eventType.includes("faulted"),
      time: `${formatUtc(event.eventTime)}${offset}`,
      json,
    };
  });
}
