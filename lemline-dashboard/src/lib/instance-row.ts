import type { InstanceSummary } from "../gen/proto/lemline/gateway/v1/gateway_pb";
import { uiStatusOf, type UiStatus } from "./status";
import { readableError } from "./errors";
import { formatDayClock, formatProtoDuration, formatRelative, formatSpan, shortInstanceId } from "./format";
import { timestampToMilliseconds } from "./proto";
import { InstanceStatus } from "../gen/proto/lemline/gateway/v1/gateway_pb";

/** What a row of the instance list shows, already in words. */
export interface InstanceRow {
  id: string;
  shortId: string;
  ns: string;
  wf: string;
  ver: string;
  status: UiStatus;
  reason: string;
  reasonSub: string;
  started: string;
  startedAt: string;
  startedExact?: number;
  duration: string;
}

export function toInstanceRow(instance: InstanceSummary, nowMs = Date.now()): InstanceRow {
  const status = uiStatusOf(instance.status);
  const startedMs = timestampToMilliseconds(instance.startedAt) ?? timestampToMilliseconds(instance.createdAt);
  let reason = "—";
  let reasonSub = "";
  if (status === "faulted") {
    const error = readableError(
      instance.error
        ? { type: instance.error.type, status: instance.error.status, title: instance.error.title }
        : undefined,
    );
    reason = error.headline;
    reasonSub = error.explanation;
  } else if (status === "running") {
    if (instance.status === InstanceStatus.PENDING) {
      reason = "Waiting to start";
      reasonSub = startedMs !== undefined ? `Since ${formatSpan(nowMs - startedMs)}` : "";
    } else {
      reason = "Running";
      reasonSub = startedMs !== undefined ? `Since ${formatSpan(nowMs - startedMs)}` : "";
    }
  }
  return {
    id: instance.instanceId,
    shortId: shortInstanceId(instance.instanceId),
    ns: instance.namespace,
    wf: instance.name,
    ver: instance.version,
    status,
    reason,
    reasonSub,
    started: formatRelative(startedMs, nowMs),
    startedAt: formatDayClock(startedMs, nowMs),
    startedExact: startedMs,
    duration: status === "running" ? "—" : formatProtoDuration(instance.duration),
  };
}
