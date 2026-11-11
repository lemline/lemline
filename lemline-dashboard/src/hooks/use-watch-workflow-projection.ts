import { create } from "@bufbuild/protobuf";
import { useQuery } from "@tanstack/react-query";
import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";
import {
  type InstanceEvent,
  type InstanceSummary,
  GetInstanceRequestSchema,
  ListInstanceEventsRequestSchema,
  WatchInstanceEventsRequestSchema,
} from "../gen/proto/lemline/gateway/v1/gateway_pb";
import { gatewayClient } from "../lib/grpc-client";
import { durationToMilliseconds, timestampToIsoString, timestampToMilliseconds } from "../lib/proto";

const EVENT_HISTORY_PAGE_SIZE = 500;
const MAX_STREAM_RETRIES = 5;

export type WorkflowRuntimeStatus = "PENDING" | "RUNNING" | "COMPLETED" | "FAULTED" | "UNKNOWN";
type TaskTerminalKind = "COMPLETED" | "FAILED";

export interface WorkflowScope {
  namespace?: string;
  name?: string;
  version?: string;
}

export interface WorkflowErrorSummary {
  title: string;
  type: string;
  status?: number;
}

export interface TimelineEventView {
  sequence: string | bigint;
  eventType: string;
  eventTime: string;
  category: string;
  taskPointer?: string;
  taskName?: string;
  payloadJson: string;
}

export interface WorkflowTaskProjection {
  pointer: string;
  name: string;
  createdAt?: string;
  startedAt?: string;
  terminalAt?: string;
  terminalKind?: TaskTerminalKind;
}

export interface WorkflowProjection {
  workflowId: string;
  namespace?: string;
  name?: string;
  version?: string;
  status: WorkflowRuntimeStatus;
  events: TimelineEventView[];
  tasks: Record<string, WorkflowTaskProjection>;
  lastCursor?: string | bigint;
  durationMs?: number;
  error?: WorkflowErrorSummary;
}

interface WorkflowProjectionSeed extends WorkflowScope {
  status?: WorkflowRuntimeStatus;
  durationMs?: number;
  error?: WorkflowErrorSummary;
}

export interface WorkflowProjectionInputEvent extends TimelineEventView {
  sortTimeMs?: number;
  scope?: WorkflowScope;
  streamIndex?: number;
}

interface UseWatchWorkflowProjectionResult {
  data?: WorkflowProjection;
  isLoading: boolean;
  isError: boolean;
  error?: Error;
  liveConnected: boolean;
  reconnect: () => void;
}

export function useWatchWorkflowProjection(workflowId: string | undefined): UseWatchWorkflowProjectionResult {
  const [projection, setProjection] = useState<WorkflowProjection | undefined>(undefined);
  const [isLoading, setIsLoading] = useState(Boolean(workflowId));
  const [isError, setIsError] = useState(false);
  const [error, setError] = useState<Error | undefined>(undefined);
  const [liveConnected, setLiveConnected] = useState(false);
  const [reconnectToken, setReconnectToken] = useState(0);

  const reconnect = useCallback(() => {
    setReconnectToken((value) => value + 1);
  }, []);

  const [streamIdentity, setStreamIdentity] = useState({ workflowId, reconnectToken });
  if (streamIdentity.workflowId !== workflowId || streamIdentity.reconnectToken !== reconnectToken) {
    setStreamIdentity({ workflowId, reconnectToken });
    setProjection(undefined);
    setIsLoading(Boolean(workflowId));
    setIsError(false);
    setError(undefined);
    setLiveConnected(false);
  }

  useEffect(() => {
    if (!workflowId) return;

    const eventsByCursor = new Map<string, WorkflowProjectionInputEvent>();
    let lastCursor: string | undefined;
    let nextStreamIndex = 0;
    let seed: WorkflowProjectionSeed | undefined;

    const controller = new AbortController();
    let disposed = false;
    let retries = 0;
    let needsReconnectToast = false;

    const publishProjection = () => {
      setProjection(buildWorkflowProjection(workflowId, eventsByCursor.values(), lastCursor, seed));
    };

    const recordEvent = (event: InstanceEvent) => {
      if (!event.cursor || eventsByCursor.has(event.cursor)) return;

      eventsByCursor.set(event.cursor, parseInstanceEvent(event, nextStreamIndex));
      lastCursor = event.cursor;
      nextStreamIndex += 1;
      publishProjection();
    };

    const loadSummaryAndHistory = async () => {
      const instanceResponse = await gatewayClient.getInstance(
        create(GetInstanceRequestSchema, { instanceId: workflowId }),
        { signal: controller.signal },
      );

      seed = instanceResponse.instance ? seedFromSummary(instanceResponse.instance) : undefined;
      publishProjection();

      let pageCursor: string | undefined;
      while (!disposed && !controller.signal.aborted) {
        const page = await gatewayClient.listInstanceEvents(
          create(ListInstanceEventsRequestSchema, {
            instanceId: workflowId,
            afterCursor: pageCursor,
            pageSize: EVENT_HISTORY_PAGE_SIZE,
          }),
          { signal: controller.signal },
        );

        for (const event of page.events) {
          recordEvent(event);
        }

        if (!page.nextCursor) break;
        pageCursor = page.nextCursor;
      }
    };

    const watchTail = async () => {
      while (!disposed && !controller.signal.aborted) {
        try {
          const request = create(WatchInstanceEventsRequestSchema, {
            instanceId: workflowId,
            afterCursor: lastCursor,
          });

          setLiveConnected(true);

          for await (const update of gatewayClient.watchInstanceEvents(request, { signal: controller.signal })) {
            const event = update.event;
            if (!event) continue;

            recordEvent(event);
            setIsLoading(false);
            setIsError(false);
            setError(undefined);
            setLiveConnected(true);

            if (needsReconnectToast) {
              toast.success("Reconnected — live updates resumed", { duration: 3000 });
              needsReconnectToast = false;
            }
            retries = 0;
          }

          if (controller.signal.aborted || disposed) return;
          throw new Error("Workflow stream disconnected");
        } catch (cause) {
          if (controller.signal.aborted || disposed) return;

          retries += 1;
          needsReconnectToast = true;
          setLiveConnected(false);

          if (retries > MAX_STREAM_RETRIES) {
            setIsError(true);
            setError(toError(cause));
            toast.error("Live updates unavailable. Reload page.", { duration: Infinity });
            return;
          }

          toast.error("Connection error — retrying...");
          await delay(backoffDelayMs(retries));
        }
      }
    };

    const run = async () => {
      try {
        await loadSummaryAndHistory();
        if (controller.signal.aborted || disposed) return;
        setIsLoading(false);
        await watchTail();
      } catch (cause) {
        if (controller.signal.aborted || disposed) return;
        setIsLoading(false);
        setIsError(true);
        setError(toError(cause));
        setLiveConnected(false);
      }
    };

    void run();

    return () => {
      disposed = true;
      controller.abort();
      setLiveConnected(false);
    };
  }, [reconnectToken, workflowId]);

  return {
    data: projection,
    isLoading,
    isError,
    error,
    liveConnected,
    reconnect,
  };
}

export function useWorkflowScopeFromWatch(workflowId: string | undefined): WorkflowScope | undefined {
  const query = useQuery({
    queryKey: ["instance-scope", workflowId],
    enabled: Boolean(workflowId),
    staleTime: 30_000,
    queryFn: async () => {
      const response = await gatewayClient.getInstance(
        create(GetInstanceRequestSchema, { instanceId: workflowId ?? "" }),
      );
      return response.instance ? scopeFromSummary(response.instance) : undefined;
    },
  });

  return query.data;
}

export function buildWorkflowProjection(
  workflowId: string,
  events: Iterable<WorkflowProjectionInputEvent>,
  lastCursor?: string | bigint,
  seed?: WorkflowProjectionSeed,
): WorkflowProjection {
  const orderedEvents = [...events].sort(compareTimelineEvents);
  const scope = resolveWorkflowScope(orderedEvents, seed);
  const tasks = buildTaskProjection(orderedEvents);

  return {
    workflowId,
    namespace: scope.namespace,
    name: scope.name,
    version: scope.version,
    status: deriveWorkflowStatus(orderedEvents, seed?.status),
    events: orderedEvents.map(stripEventInternals),
    tasks,
    lastCursor,
    durationMs: deriveWorkflowDuration(orderedEvents, seed?.durationMs),
    error: seed?.error,
  };
}

function parseInstanceEvent(event: InstanceEvent, streamIndex: number): WorkflowProjectionInputEvent {
  const cloudEvent = parseCloudEventJson(event.cloudEventJson);
  const eventType = normalizeText(event.eventType) ?? "unknown";
  const eventTime = timestampToIsoString(event.eventTime) ?? normalizeText(cloudEvent?.time) ?? "";
  const category = deriveCategory(eventType);
  const data = isObject(cloudEvent?.data) ? cloudEvent.data : undefined;
  const taskPointer = normalizeText(data?.task) ?? normalizeText(data?.taskPointer);
  const taskName = normalizeText(data?.taskName) ?? inferTaskName(taskPointer);

  return {
    sequence: event.cursor,
    eventType,
    eventTime,
    category,
    taskPointer,
    taskName,
    payloadJson: event.cloudEventJson,
    scope: extractWorkflowScopeFromCloudEvent(cloudEvent),
    sortTimeMs: timestampToMilliseconds(event.eventTime) ?? parseTimeMs(eventTime),
    streamIndex,
  };
}

function seedFromSummary(instance: InstanceSummary): WorkflowProjectionSeed {
  return {
    namespace: instance.namespace,
    name: instance.name,
    version: instance.version,
    status: mapSummaryStatus(instance),
    durationMs: durationToMilliseconds(instance.duration),
    error: instance.error
      ? {
          title: instance.error.title,
          type: instance.error.type,
          status: instance.error.status,
        }
      : undefined,
  };
}

function scopeFromSummary(instance: InstanceSummary): WorkflowScope {
  return {
    namespace: instance.namespace,
    name: instance.name,
    version: instance.version,
  };
}

function mapSummaryStatus(instance: InstanceSummary): WorkflowRuntimeStatus {
  switch (instance.status) {
    case 1:
      return "PENDING";
    case 2:
      return "RUNNING";
    case 3:
      return "COMPLETED";
    case 4:
      return "FAULTED";
    default:
      return "UNKNOWN";
  }
}

function extractWorkflowScopeFromCloudEvent(cloudEvent: Record<string, unknown> | undefined): WorkflowScope | undefined {
  if (!cloudEvent) return undefined;

  const namespace =
    normalizeText(cloudEvent.lemlineworkflownamespace) ??
    normalizeText(getNested(cloudEvent, "data", "definition", "namespace"));
  const name =
    normalizeText(cloudEvent.lemlineworkflowname) ??
    normalizeText(getNested(cloudEvent, "data", "definition", "name"));
  const version =
    normalizeText(cloudEvent.lemlineworkflowversion) ??
    normalizeText(getNested(cloudEvent, "data", "definition", "version"));

  if (!namespace && !name && !version) return undefined;
  return { namespace, name, version };
}

function resolveWorkflowScope(
  events: WorkflowProjectionInputEvent[],
  seed?: WorkflowProjectionSeed,
): WorkflowScope {
  for (const event of events) {
    if (event.scope?.namespace || event.scope?.name || event.scope?.version) {
      return {
        namespace: event.scope.namespace,
        name: event.scope.name,
        version: event.scope.version,
      };
    }
  }

  return {
    namespace: seed?.namespace,
    name: seed?.name,
    version: seed?.version,
  };
}

function buildTaskProjection(events: WorkflowProjectionInputEvent[]): Record<string, WorkflowTaskProjection> {
  const projections = new Map<string, WorkflowTaskProjection>();

  for (const event of events) {
    if (!event.eventType.includes(".task.")) continue;
    const pointer = event.taskPointer?.trim() || `task:${event.taskName?.trim() || "unknown"}`;
    const existing = projections.get(pointer) ?? {
      pointer,
      name: event.taskName?.trim() || inferTaskName(pointer) || pointer,
    };

    const kind = taskEventKind(event.eventType);
    if (!existing.name && event.taskName?.trim()) {
      existing.name = event.taskName.trim();
    }

    if (kind === "created" && !existing.createdAt && event.eventTime) {
      existing.createdAt = event.eventTime;
    }
    if (kind === "started" && !existing.startedAt && event.eventTime) {
      existing.startedAt = event.eventTime;
    }
    if ((kind === "completed" || kind === "failed") && event.eventTime) {
      existing.terminalAt = event.eventTime;
      existing.terminalKind = kind === "completed" ? "COMPLETED" : "FAILED";
    }

    projections.set(pointer, existing);
  }

  return Object.fromEntries(projections.entries());
}

function deriveWorkflowStatus(
  events: WorkflowProjectionInputEvent[],
  fallback: WorkflowRuntimeStatus = "UNKNOWN",
): WorkflowRuntimeStatus {
  if (events.some((event) => event.eventType.includes("workflow.faulted"))) return "FAULTED";
  if (events.some((event) => event.eventType.includes("workflow.completed"))) return "COMPLETED";
  if (events.some((event) => event.eventType.includes("workflow.started"))) return "RUNNING";
  if (events.length > 0) return "PENDING";
  return fallback;
}

function deriveWorkflowDuration(
  events: WorkflowProjectionInputEvent[],
  fallback?: number,
): number | undefined {
  const started = events.find((event) => event.eventType.includes("workflow.started"));
  const completed =
    lastMatching(events, (event) => event.eventType.includes("workflow.completed")) ??
    lastMatching(events, (event) => event.eventType.includes("workflow.faulted"));

  if (!started?.eventTime || !completed?.eventTime) {
    return fallback;
  }

  const startedMs = Date.parse(started.eventTime);
  const completedMs = Date.parse(completed.eventTime);
  if (Number.isNaN(startedMs) || Number.isNaN(completedMs)) {
    return fallback;
  }

  return completedMs - startedMs;
}

function compareTimelineEvents(left: WorkflowProjectionInputEvent, right: WorkflowProjectionInputEvent): number {
  if (
    left.sortTimeMs !== undefined &&
    right.sortTimeMs !== undefined &&
    left.sortTimeMs !== right.sortTimeMs
  ) {
    return left.sortTimeMs - right.sortTimeMs;
  }

  if (
    left.streamIndex !== undefined &&
    right.streamIndex !== undefined &&
    left.streamIndex !== right.streamIndex
  ) {
    return left.streamIndex - right.streamIndex;
  }

  const sequenceComparison = compareSequenceValues(left.sequence, right.sequence);
  if (sequenceComparison !== 0) return sequenceComparison;

  return (left.eventType || "").localeCompare(right.eventType || "");
}

function compareSequenceValues(
  left: WorkflowProjectionInputEvent["sequence"],
  right: WorkflowProjectionInputEvent["sequence"],
): number {
  if (typeof left === "bigint" && typeof right === "bigint") {
    if (left === right) return 0;
    return left < right ? -1 : 1;
  }

  const leftNumber = asComparableNumber(left);
  const rightNumber = asComparableNumber(right);
  if (leftNumber !== undefined && rightNumber !== undefined && leftNumber !== rightNumber) {
    return leftNumber < rightNumber ? -1 : 1;
  }

  const leftText = left.toString();
  const rightText = right.toString();
  return leftText.localeCompare(rightText);
}

function stripEventInternals(event: WorkflowProjectionInputEvent): TimelineEventView {
  return {
    sequence: event.sequence,
    eventType: event.eventType,
    eventTime: event.eventTime,
    category: event.category,
    taskPointer: event.taskPointer,
    taskName: event.taskName,
    payloadJson: event.payloadJson,
  };
}

function deriveCategory(eventType: string): string {
  if (eventType.includes(".workflow.")) return "WORKFLOW";
  if (eventType.includes(".task.")) return "TASK";
  return "UNKNOWN";
}

function taskEventKind(eventType: string): "created" | "started" | "completed" | "failed" | "other" {
  if (eventType.includes("task.created")) return "created";
  if (eventType.includes("task.started")) return "started";
  if (eventType.includes("task.completed")) return "completed";
  if (eventType.includes("task.faulted")) return "failed";
  return "other";
}

function inferTaskName(pointer: string | undefined): string | undefined {
  if (!pointer) return undefined;
  const segments = pointer.split("/").filter((segment) => segment.length > 0);
  return segments[segments.length - 1];
}

function parseCloudEventJson(value: string): Record<string, unknown> | undefined {
  try {
    const parsed = JSON.parse(value);
    return isObject(parsed) ? parsed : undefined;
  } catch {
    return undefined;
  }
}

function parseTimeMs(value: string): number | undefined {
  if (!value) return undefined;
  const ms = Date.parse(value);
  return Number.isFinite(ms) ? ms : undefined;
}

function normalizeText(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}

function getNested(root: Record<string, unknown>, ...path: string[]): unknown {
  let current: unknown = root;
  for (const segment of path) {
    if (!isObject(current)) return undefined;
    current = current[segment];
  }
  return current;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function asComparableNumber(value: string | bigint): bigint | undefined {
  if (typeof value === "bigint") return value;
  const trimmed = value.trim();
  if (!/^-?\d+$/.test(trimmed)) return undefined;
  try {
    return BigInt(trimmed);
  } catch {
    return undefined;
  }
}

function lastMatching<T>(items: T[], predicate: (item: T) => boolean): T | undefined {
  for (let index = items.length - 1; index >= 0; index -= 1) {
    if (predicate(items[index])) return items[index];
  }
  return undefined;
}

function toError(cause: unknown): Error {
  if (cause instanceof Error) return cause;
  return new Error("Unexpected workflow stream error");
}

function backoffDelayMs(retryAttempt: number): number {
  return Math.min(1000 * 2 ** Math.max(0, retryAttempt - 1), 30_000);
}

function delay(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}
