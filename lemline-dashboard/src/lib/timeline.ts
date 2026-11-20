import type { TimelineEventView, WorkflowRuntimeStatus } from "../hooks/use-watch-workflow-projection";
import { readableError, type RawError } from "./errors";
import { formatElapsedTick, formatSpan, prettyJson } from "./format";
import { isTaskPointer, shortEventType, taskAt, taskNamesOf } from "./workflow-source";

/**
 * Builds what the instance page draws from the lifecycle events of one instance:
 * one row per task, each made of segments (ran, running, failed, waiting before retry,
 * waiting for an event), on a time axis where long idle waits are shortened.
 */

export type SegmentKind = "ran" | "running" | "failed" | "backoff" | "waiting" | "scheduled" | "interrupted" | "group";
/**
 * "scheduled": waiting to start. From task.created (or, for a retry attempt, from its planned
 * retry time) until task.started; still drawn once the task has started, and open-ended while it
 * has not. It is waiting for execution, not intentional delay ("backoff").
 * "interrupted": still open when the instance ended, so it never finished.
 */
export type StepState = "completed" | "running" | "waiting" | "failed" | "retrying" | "scheduled" | "interrupted";

export interface Segment {
  start: number;
  end: number;
  kind: SegmentKind;
}

export interface Attempt {
  start: number;
  end?: number;
  outcome: "succeeded" | "failed" | "running" | "interrupted";
  error?: RawError;
  ownerId?: string;
  ownerAttempt?: number;
}

export interface StepRow {
  /** Unique per row: the pointer, plus the iteration when the task ran several times (loops). */
  id: string;
  pointer: string;
  /** 1-based iteration, only when the task ran more than once (each run of a loop is a row). */
  iteration?: number;
  label: string;
  indent: number;
  typeLabel: string;
  state: StepState;
  segments: Segment[];
  attempts: Attempt[];
  start: number;
  end?: number;
  input?: unknown;
  output?: unknown;
  hasOutput: boolean;
  error?: RawError;
  listensTo: string[];
  isGroup: boolean;
  /** A leaf body still does work when extensions make its row a group. */
  hasActiveBody?: boolean;
  ownerId?: string;
  ownerAttempt?: number;
  /**
   * Time spent waiting to start (task.created → task.started) before the first attempt, in ms.
   * Undefined when it is not known: no task.created was recorded for this run (older runtimes,
   * a retried block, or a creation event not received yet). Never guessed as zero.
   */
  waitedToStart?: number;
  note: string;
  detail: string;
}

export interface Tick {
  pct: number;
  label: string;
  strong?: boolean;
}

export interface TimelineModel {
  t0: number;
  tEnd: number;
  rows: StepRow[];
  ticks: Tick[];
  toPct: (time: number) => number;
}

interface ParsedEvent {
  view: TimelineEventView;
  time: number;
  /** Position in the event stream, as the runtime emitted it. */
  order: number;
  data: Record<string, unknown>;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function cloudEventOf(view: TimelineEventView): Record<string, unknown> | undefined {
  try {
    const parsed = JSON.parse(view.payloadJson);
    return isObject(parsed) ? parsed : undefined;
  } catch {
    return undefined;
  }
}

function dataOf(view: TimelineEventView): Record<string, unknown> {
  const cloudEvent = cloudEventOf(view);
  return cloudEvent && isObject(cloudEvent.data) ? cloudEvent.data : {};
}

function errorOf(data: Record<string, unknown>): RawError | undefined {
  const error = data.error;
  if (!isObject(error)) return undefined;
  return {
    type: typeof error.type === "string" ? error.type : undefined,
    status: typeof error.status === "number" ? error.status : undefined,
    title: typeof error.title === "string" ? error.title : undefined,
    detail: typeof error.detail === "string" ? error.detail : undefined,
    instance: typeof error.instance === "string" ? error.instance : undefined,
  };
}

function kindOf(eventType: string): "created" | "started" | "completed" | "faulted" | "retried" | "other" {
  if (eventType.includes("task.created")) return "created";
  if (eventType.includes("task.started")) return "started";
  if (eventType.includes("task.completed")) return "completed";
  if (eventType.includes("task.faulted")) return "faulted";
  if (eventType.includes("task.retried")) return "retried";
  return "other";
}

/**
 * `order` is the event's position in the stream: its `streamIndex` when given (the instance
 * page numbers events as they arrive), otherwise its place in the array (the instance list reads
 * the history in stream order). It only breaks ties between events stamped at the same time:
 * the runtime writes the events of one step in no particular order (a task.retried before the
 * task.faulted that caused it, a task.started before its task.created).
 */
function parseEvents(events: TimelineEventView[]): ParsedEvent[] {
  return events
    .map((view, index) => ({ view, position: view.streamIndex ?? index }))
    .sort((left, right) => left.position - right.position)
    .map(({ view }, order) => ({ view, time: Date.parse(view.eventTime), order, data: dataOf(view) }))
    .filter((event) => Number.isFinite(event.time));
}

/**
 * The events of one task in the order they happened: by time, except a task.retried, which is
 * stamped with the planned retry time. It is put right after the failure it follows (the last
 * task.faulted stamped before that planned time), where the wait before retry starts.
 */
function inHappeningOrder(list: ParsedEvent[]): ParsedEvent[] {
  const faults = list.filter((event) => kindOf(event.view.eventType) === "faulted");
  const at = (event: ParsedEvent) => {
    if (kindOf(event.view.eventType) !== "retried") return event.time;
    const failure = faults.filter((fault) => fault.time <= event.time).reduce<ParsedEvent | undefined>(
      (latest, fault) => (latest === undefined || fault.time >= latest.time ? fault : latest),
      undefined,
    );
    return failure?.time ?? event.time;
  };
  const isRetry = (event: ParsedEvent) => (kindOf(event.view.eventType) === "retried" ? 1 : 0);
  // Equal timestamps do not tell what came first: a task is created before it starts by
  // definition, so at the same time a task.created comes first. Arrival order breaks other ties.
  const createdFirst = (left: ParsedEvent, right: ParsedEvent) => {
    const l = kindOf(left.view.eventType);
    const r = kindOf(right.view.eventType);
    if (l === "created" && r === "started") return -1;
    if (l === "started" && r === "created") return 1;
    return 0;
  };
  // Same time: the stream order, except that a retry always follows its failure.
  return [...list].sort((left, right) =>
    at(left) - at(right) || isRetry(left) - isRetry(right) || createdFirst(left, right) || left.order - right.order);
}

export function workflowBounds(events: TimelineEventView[], status: WorkflowRuntimeStatus, nowMs: number) {
  const parsed = parseEvents(events);
  const created = parsed.find((event) => event.view.eventType.includes("workflow.created"));
  const started = parsed.find((event) => event.view.eventType.includes("workflow.started"));
  const ended = [...parsed]
    .reverse()
    .find((event) => event.view.eventType.includes("workflow.completed") || event.view.eventType.includes("workflow.faulted"));
  const first = started?.time ?? created?.time ?? parsed[0]?.time ?? nowMs;
  const terminal = status === "COMPLETED" || status === "FAULTED";
  // A task.retried carries the planned retry time, not the time it happened.
  const lastHappened = parsed.filter((event) => kindOf(event.view.eventType) !== "retried").pop();
  const end = ended?.time ?? (terminal ? (lastHappened?.time ?? nowMs) : nowMs);
  // created → started: waiting for a worker to begin the instance. Unknown (undefined) when either
  // event is missing; clamped at zero when clocks of different hosts disagree.
  const waitedToStart = created && started ? Math.max(0, started.time - created.time) : undefined;
  return {
    start: Math.min(created?.time ?? first, first),
    startedAt: first,
    createdAt: created?.time,
    waitedToStart,
    waitingToStart: Boolean(created && !started),
    end,
    terminal,
    input: created ? created.data.input : undefined,
    output: ended && ended.view.eventType.includes("workflow.completed") ? ended.data.output : undefined,
    hasOutput: Boolean(ended && ended.view.eventType.includes("workflow.completed") && "output" in ended.data),
    error: ended && ended.view.eventType.includes("workflow.faulted") ? errorOf(ended.data) : undefined,
  };
}

const CONTAINER_KINDS = new Set(["fork", "do", "try", "for"]);

export function buildTimeline(
  events: TimelineEventView[],
  status: WorkflowRuntimeStatus,
  root: Record<string, unknown> | undefined,
  nowMs: number,
): TimelineModel {
  const parsed = parseEvents(events);
  const bounds = workflowBounds(events, status, nowMs);
  const tEnd = Math.max(bounds.end, bounds.startedAt + 1);

  const byPointer = new Map<string, ParsedEvent[]>();
  const add = (pointer: string, event: ParsedEvent) => {
    const list = byPointer.get(pointer) ?? [];
    list.push(event);
    byPointer.set(pointer, list);
  };
  const blockRetries: { block: string; event: ParsedEvent }[] = [];
  const faults: { pointer: string; time: number; origin?: string }[] = [];
  for (const event of parsed) {
    if (!event.view.eventType.includes(".task.")) continue;
    const pointer = event.view.taskPointer?.trim();
    if (!pointer) continue;
    if (!isTaskPointer(pointer, root)) {
      if (kindOf(event.view.eventType) === "retried") blockRetries.push({ block: pointer, event });
      continue;
    }
    if (kindOf(event.view.eventType) === "faulted") faults.push({ pointer, time: event.time, origin: typeof errorOf(event.data)?.instance === "string" ? errorOf(event.data)?.instance as string : undefined });
    add(pointer, event);
  }
  // The runtime announces a retry on the try block it re-enters ("/do/1/charge/try"), not on the
  // task that failed in it: the retry goes to the task of that block that failed last before its
  // planned time.
  for (const { block, event } of blockRetries) {
    const failed = faults
      .filter((fault) => fault.time <= event.time && fault.pointer.startsWith(`${block}/`))
      .reduce<{ pointer: string; time: number; origin?: string } | undefined>(
        (latest, fault) => (latest === undefined || fault.time >= latest.time ? fault : latest),
        undefined,
      );
    if (failed) {
      const origin = failed.origin ?? failed.pointer;
      const chain = new Set(faults.filter((fault) => fault.time <= event.time && fault.pointer.startsWith(`${block}/`) &&
        (fault.pointer === origin || origin.startsWith(`${fault.pointer}/`))).map((fault) => fault.pointer));
      chain.add(failed.pointer);
      for (const pointer of chain) add(pointer, event);
    }
  }

  const pointers = Array.from(byPointer.keys());
  const rows: StepRow[] = [];
  for (const [pointer, list] of byPointer.entries()) {
    const info = taskAt(root, pointer);
    const isGroup =
      (info.kind !== undefined && CONTAINER_KINDS.has(info.kind)) ||
      pointers.some((other) => other !== pointer && other.startsWith(`${pointer}/`));
    // A function call is control flow: its time is the time of the tasks inside it.
    const callsFunction = pointers.some((other) => other.startsWith(`${pointer}/_fn/`));
    const runs = splitRuns(inHappeningOrder(list));
    runs.forEach((run, index) => {
      const iteration = runs.length > 1 ? index + 1 : undefined;
      const row = buildRow(pointer, iteration, run, info, isGroup, bounds.terminal, tEnd);
      rows.push(callsFunction ? { ...row, hasActiveBody: false } : row);
    });
  }

  // Associate each extension invocation with the enclosing owner's actual attempt interval.
  for (const row of rows) {
    const owner = taskAt(root, row.pointer).extension?.owner;
    if (!owner) continue;
    for (const attempt of row.attempts) {
      const parent = rows.filter((candidate) => candidate.pointer === owner && candidate.start <= attempt.start &&
        (candidate.end ?? tEnd) >= attempt.start).sort((a, b) => b.start - a.start)[0];
      if (!parent) continue;
      attempt.ownerId = parent.id;
      const ownerAttempt = parent.attempts.findIndex((item) => item.start <= attempt.start && (item.end ?? tEnd) >= attempt.start);
      if (ownerAttempt >= 0) attempt.ownerAttempt = ownerAttempt + 1;
    }
    row.ownerId = row.attempts[0]?.ownerId;
    row.ownerAttempt = row.attempts[0]?.ownerAttempt;
  }
  rows.sort(
    (left, right) =>
      left.start - right.start ||
      left.indent - right.indent ||
      left.pointer.localeCompare(right.pointer) ||
      (left.iteration ?? 0) - (right.iteration ?? 0),
  );
  // A task may be stamped a few milliseconds before the workflow, and a task waits to start before
  // it starts: the axis starts at the earliest of these.
  const t0 = Math.min(bounds.startedAt, ...rows.map((row) => row.start), ...rows.flatMap((row) => row.segments.map((segment) => segment.start)));
  const minIndent = rows.reduce((min, row) => Math.min(min, row.indent), Number.POSITIVE_INFINITY);
  for (const row of rows) row.indent -= Number.isFinite(minIndent) ? minIndent : 0;

  const scale = buildScale(rows, t0, tEnd);
  const ticks = buildTicks(scale, t0, tEnd, !bounds.terminal);
  for (const row of rows) {
    row.note = noteOf(row, tEnd);
    row.detail = detailOf(row, tEnd);
  }
  return { t0, tEnd, rows, ticks, toPct: scale.toPct };
}

/**
 * The runs of one task, in order: a loop runs the same tasks (same pointers) at each iteration.
 * A run ends when the task completes, or fails without a retry; the next task.created or
 * task.started then begins a new run. A retry stays in its run, as further attempts.
 */
function splitRuns(ordered: ParsedEvent[]): ParsedEvent[][] {
  const runs: ParsedEvent[][] = [];
  let current: ParsedEvent[] = [];
  let ended = false;
  for (const event of ordered) {
    const kind = kindOf(event.view.eventType);
    if (ended && (kind === "created" || kind === "started")) {
      runs.push(current);
      current = [];
      ended = false;
    }
    current.push(event);
    if (kind === "completed" || kind === "faulted") ended = true;
    else if (kind === "retried") ended = false;
  }
  if (current.length > 0) runs.push(current);
  return runs;
}

function buildRow(
  pointer: string,
  iteration: number | undefined,
  /** The run's events, in the order they happened. */
  ordered: ParsedEvent[],
  info: ReturnType<typeof taskAt>,
  isGroup: boolean,
  terminal: boolean,
  tEnd: number,
): StepRow {
  const names = taskNamesOf(pointer);
  const attempts: Attempt[] = [];
  const segments: Segment[] = [];
  let open: Attempt | null = null;
  let backoffFrom: number | null = null;
  // Planned time of the pending retry (task.retried is stamped with it): the end of the backoff.
  let retryAt: number | null = null;
  // Announced (task.created), or eligible again after a retry, and not started yet.
  let scheduledFrom: number | null = null;
  let waitedToStart: number | undefined;
  let input: unknown;
  let output: unknown;
  let hasOutput = false;
  let lastError: RawError | undefined;

  /**
   * Ends the intentional delay before a retry at its planned time (or earlier, when the attempt
   * already resumed, as stamped by another clock): from then on the attempt is eligible, and
   * any further time is waiting to start.
   */
  const closeBackoff = (time: number) => {
    if (backoffFrom !== null) {
      const end = Math.max(backoffFrom, Math.min(time, retryAt ?? time));
      segments.push({ start: backoffFrom, end, kind: "backoff" });
      backoffFrom = null;
      retryAt = null;
      if (scheduledFrom === null && end < time) scheduledFrom = end;
    }
  };
  const openAttempt = (time: number) => {
    closeBackoff(time);
    if (scheduledFrom !== null) {
      // Clocks of different hosts can stamp a creation after the start: never a negative wait.
      const waited = Math.max(0, time - scheduledFrom);
      if (attempts.length === 0 && waitedToStart === undefined) waitedToStart = waited;
      if (waited > 0) segments.push({ start: scheduledFrom, end: time, kind: "scheduled" });
      scheduledFrom = null;
    }
    open = { start: time, outcome: "running" };
    attempts.push(open);
  };

  let previousTime: number | undefined;
  for (const event of ordered) {
    const kind = kindOf(event.view.eventType);
    const eventPreviousTime = previousTime;
    if (kind !== "retried") previousTime = event.time;
    if (kind === "created") {
      if (input === undefined && "input" in event.data) input = event.data.input;
      // The task waits to start until its task.started. After a retry, the backoff ends at the
      // planned retry time (or at this creation, if stamped earlier) and the wait follows it.
      if (backoffFrom !== null) closeBackoff(event.time);
      if (!open && scheduledFrom === null) scheduledFrom = event.time;
    } else if (kind === "started") {
      if (!open) openAttempt(event.time);
    } else if (kind === "completed") {
      if (!open) openAttempt(event.time);
      const current = open as unknown as Attempt;
      current.end = event.time;
      current.outcome = "succeeded";
      open = null;
      if ("output" in event.data) {
        output = event.data.output;
        hasOutput = true;
      }
    } else if (kind === "faulted") {
      if (!open) openAttempt(event.time);
      const current = open as unknown as Attempt;
      current.end = event.time;
      current.outcome = "failed";
      current.error = errorOf(event.data);
      lastError = current.error ?? lastError;
      open = null;
    } else if (kind === "retried") {
      // Stamped with the planned retry time: the wait before retry starts when the attempt
      // failed, at its task.faulted, or else at the last event seen before the retry.
      const failedAt = Math.min(
        open ? (eventPreviousTime ?? event.time) : (attempts[attempts.length - 1]?.end ?? eventPreviousTime ?? event.time),
        event.time,
      );
      if (open) {
        const current = open as Attempt;
        current.end = failedAt;
        current.outcome = "failed";
        current.error = current.error ?? lastError;
        open = null;
      }
      backoffFrom = failedAt;
      retryAt = event.time;
    }
  }

  const waitsForEvent = info.kind === "listen" || info.kind === "wait";
  const start = attempts[0]?.start ?? ordered[0]?.time ?? tEnd;
  let state: StepState = "completed";
  const last = attempts[attempts.length - 1];
  if (backoffFrom !== null) {
    if (!terminal) {
      state = "retrying";
      // Past its planned time, a retry that has not started yet is waiting to start.
      const backoffEnd = retryAt !== null && retryAt < tEnd ? Math.max(backoffFrom, retryAt) : tEnd;
      segments.push({ start: backoffFrom, end: backoffEnd, kind: "backoff" });
      if (backoffEnd < tEnd) segments.push({ start: backoffEnd, end: tEnd, kind: "scheduled" });
    } else {
      state = "failed";
    }
    backoffFrom = null;
  } else if (scheduledFrom !== null) {
    // Waiting to start, or cut short before starting if the instance has ended.
    state = terminal ? "interrupted" : "scheduled";
    segments.push({ start: scheduledFrom, end: tEnd, kind: terminal ? "interrupted" : "scheduled" });
  } else if (last?.outcome === "running") {
    if (terminal) {
      // No task.completed: the instance ended while this task ran (another branch of a fork
      // ended it, or it failed elsewhere).
      last.end = tEnd;
      last.outcome = "interrupted";
      state = "interrupted";
    } else {
      state = waitsForEvent ? "waiting" : "running";
    }
  } else if (last?.outcome === "failed") {
    state = "failed";
  }

  for (const attempt of attempts) {
    const end = attempt.end ?? tEnd;
    let kind: SegmentKind = attempt.outcome === "failed" ? "failed" : attempt.outcome === "running" ? "running" : "ran";
    if (waitsForEvent && attempt.outcome !== "failed") kind = "waiting";
    if (attempt.outcome === "interrupted") kind = "interrupted";
    segments.push({ start: attempt.start, end: Math.max(end, attempt.start), kind });
  }
  segments.sort((left, right) => left.start - right.start);

  const ongoing = state === "running" || state === "waiting" || state === "retrying" || state === "scheduled";
  const end = ongoing ? undefined : state === "interrupted" ? tEnd : (last?.end ?? start);
  // A group keeps its wait to start (the instance's first block waits in the queue like a task).
  const groupSegments: Segment[] = isGroup
    ? [...segments.filter((segment) => segment.kind === "scheduled" && segment.end <= start), { start, end: end ?? tEnd, kind: "group" }]
    : segments;

  let typeLabel = info.typeLabel || "task";
  if (info.extension) typeLabel += ` · ${info.extension.name} · ${info.extension.phase}`;
  if (iteration !== undefined) typeLabel += ` · run ${iteration}`;
  if (attempts.length > 1) typeLabel += ` · ${attempts.length} attempts`;

  return {
    id: iteration !== undefined ? `${pointer}#${iteration}` : pointer,
    pointer,
    iteration,
    label: names[names.length - 1] ?? pointer,
    indent: Math.max(0, names.length - 1),
    typeLabel,
    state,
    segments: groupSegments,
    attempts,
    start,
    end,
    input,
    output,
    hasOutput,
    error: state === "failed" ? (last?.error ?? lastError) : undefined,
    listensTo: info.listensTo,
    waitedToStart,
    isGroup,
    hasActiveBody: isGroup && info.kind !== undefined && !CONTAINER_KINDS.has(info.kind) && !waitsForEvent,
    note: "",
    detail: "",
  };
}

interface Scale {
  toPct: (time: number) => number;
  breaks: { start: number; end: number; fromPct: number; toPct: number }[];
}

const AXIS_MAX_PCT = 94;

/** Long stretches where no task works (waiting or idle) are drawn with a fixed, short width. */
/**
 * The owner's own work: its attempts minus the time its extension blocks run. Those blocks are
 * rows of their own, and their waits must stay compressible.
 */
function activeBodyOf(owner: StepRow, rows: StepRow[], tEnd: number): { start: number; end: number }[] {
  const cuts = rows
    .filter((row) => row.pointer.startsWith(`${owner.pointer}/_ext/`))
    .flatMap((row) => row.attempts.map((attempt) => ({ start: attempt.start, end: attempt.end ?? tEnd })));
  let pieces = owner.attempts.map((attempt) => ({ start: attempt.start, end: attempt.end ?? tEnd }));
  for (const cut of cuts) {
    pieces = pieces.flatMap((piece) => cut.end <= piece.start || cut.start >= piece.end
      ? [piece]
      : [{ start: piece.start, end: cut.start }, { start: cut.end, end: piece.end }].filter((part) => part.end > part.start));
  }
  return pieces;
}

function buildScale(rows: StepRow[], t0: number, tEnd: number): Scale {
  const total = tEnd - t0;
  const busy = rows
    .filter((row) => !row.isGroup || row.hasActiveBody)
    .flatMap((row) => row.hasActiveBody
      ? activeBodyOf(row, rows, tEnd)
      : row.segments.filter((segment) => segment.kind !== "waiting" && segment.kind !== "backoff" && segment.kind !== "scheduled"))
    .map((segment) => [Math.max(t0, segment.start), Math.min(tEnd, segment.end)] as const)
    .filter(([start, end]) => end >= start)
    .sort((left, right) => left[0] - right[0]);

  const gaps: { start: number; end: number }[] = [];
  let cursor = t0;
  for (const [start, end] of busy) {
    if (start > cursor) gaps.push({ start: cursor, end: start });
    cursor = Math.max(cursor, end);
  }
  if (tEnd > cursor) gaps.push({ start: cursor, end: tEnd });

  const threshold = Math.max(5_000, total * 0.25);
  const candidates = gaps.filter((gap) => gap.end - gap.start > threshold).slice(0, 3);
  // Shortening only makes sense next to work drawn to scale.
  const long = total - candidates.reduce((sum, gap) => sum + (gap.end - gap.start), 0) > 0 ? candidates : [];
  const compressed = long.reduce((sum, gap) => sum + (gap.end - gap.start), 0);
  const normal = total - compressed;
  // About a quarter of the axis for one shortened wait, less each when there are several.
  const breakPct = long.length > 0 ? Math.min(24, 45 / long.length) : 0;
  const normalPct = AXIS_MAX_PCT - breakPct * long.length;

  const breaks: Scale["breaks"] = [];
  const toPct = (time: number) => {
    const t = Math.min(Math.max(time, t0), tEnd);
    if (long.length === 0) return total <= 0 ? 0 : ((t - t0) / total) * AXIS_MAX_PCT;
    let pct = 0;
    let previous = t0;
    for (const gap of long) {
      const normalSpan = Math.min(t, gap.start) - previous;
      if (normalSpan > 0) pct += normal > 0 ? (normalSpan / normal) * normalPct : 0;
      if (t <= gap.start) return pct;
      const inGap = Math.min(t, gap.end) - gap.start;
      pct += (inGap / (gap.end - gap.start)) * breakPct;
      if (t <= gap.end) return pct;
      previous = gap.end;
    }
    if (t > previous && normal > 0) pct += ((t - previous) / normal) * normalPct;
    return pct;
  };
  for (const gap of long) breaks.push({ ...gap, fromPct: toPct(gap.start), toPct: toPct(gap.end) });
  return { toPct, breaks };
}

const NICE_STEPS = [
  100, 200, 500, 1_000, 2_000, 5_000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 900_000, 1_800_000,
  3_600_000, 7_200_000, 21_600_000, 43_200_000, 86_400_000,
];
const MIN_TICK_GAP_PCT = 9;

function buildTicks(scale: Scale, t0: number, tEnd: number, live: boolean): Tick[] {
  const candidates: (Tick & { priority: number })[] = [{ pct: 0, label: "0 s", priority: 3 }];
  for (const gap of scale.breaks) {
    candidates.push({
      pct: (gap.fromPct + gap.toPct) / 2 - 3,
      label: `⋯ ${formatSpan(gap.end - gap.start)} ⋯`,
      strong: true,
      priority: 4,
    });
  }
  candidates.push({ pct: scale.toPct(tEnd), label: live ? "now" : formatElapsedTick(tEnd - t0), priority: 3 });

  // Regular ticks in the parts drawn to scale.
  const runs: [number, number][] = [];
  let previous = t0;
  for (const gap of scale.breaks) {
    runs.push([previous, gap.start]);
    previous = gap.end;
  }
  runs.push([previous, tEnd]);
  for (const [start, end] of runs) {
    const span = end - start;
    const spanPct = scale.toPct(end) - scale.toPct(start);
    if (span <= 0 || spanPct <= 0) continue;
    const msPerGap = (span / spanPct) * MIN_TICK_GAP_PCT;
    const step = NICE_STEPS.find((value) => value >= msPerGap) ?? NICE_STEPS[NICE_STEPS.length - 1];
    for (let elapsed = Math.ceil((start - t0) / step) * step; t0 + elapsed < end; elapsed += step) {
      if (elapsed <= 0) continue;
      candidates.push({ pct: scale.toPct(t0 + elapsed), label: formatElapsedTick(elapsed), priority: 1 });
    }
  }

  const kept: Tick[] = [];
  for (const candidate of [...candidates].sort((left, right) => right.priority - left.priority)) {
    const width = candidate.strong ? 12 : MIN_TICK_GAP_PCT - 2;
    if (kept.every((tick) => Math.abs(tick.pct - candidate.pct) >= (tick.strong ? 12 : width))) {
      kept.push({ pct: Math.max(0, candidate.pct), label: candidate.label, strong: candidate.strong });
    }
  }
  return kept.sort((left, right) => left.pct - right.pct);
}

function errorCode(error?: RawError): string {
  if (error?.status && error.status > 0) return String(error.status);
  return "failed";
}

function noteOf(row: StepRow, nowMs: number): string {
  const duration = (row.end ?? nowMs) - row.start;
  if (row.isGroup && row.attempts.length === 1 && (row.state === "completed" || row.state === "running")) return row.end === undefined ? `running for ${formatSpan(duration)}…` : formatSpan(duration);
  if (row.state === "waiting") return `waiting for ${formatSpan(duration)}…`;
  if (row.state === "scheduled") return `waiting to start for ${formatSpan(duration)}…`;
  if (row.attempts.length > 1 || row.state === "retrying") {
    const parts: string[] = [];
    row.attempts.forEach((attempt, index) => {
      if (attempt.outcome === "failed") {
        parts.push(errorCode(attempt.error));
        const next = row.attempts[index + 1];
        const pause = (next?.start ?? (row.state === "retrying" ? nowMs : attempt.end ?? 0)) - (attempt.end ?? 0);
        if (next || row.state === "retrying") parts.push(`retry in ${formatSpan(Math.max(0, pause))}`);
      } else if (attempt.outcome === "succeeded") {
        parts.push("ok");
      } else if (attempt.outcome === "interrupted") {
        parts.push("interrupted");
      } else {
        parts.push("running…");
      }
    });
    return parts.join(" → ");
  }
  if (row.state === "failed") {
    const error = readableError(row.error);
    const code = error.headline.startsWith("HTTP ") && row.error?.status ? `HTTP ${row.error.status}` : error.headline;
    return `${code} after ${formatSpan(duration)}`;
  }
  if (row.state === "running") return `running for ${formatSpan(duration)}…`;
  if (row.state === "interrupted") return `interrupted after ${formatSpan(duration)}`;
  if (row.listensTo.length > 0 || row.segments.some((segment) => segment.kind === "waiting")) return `waited ${formatSpan(duration)}`;
  return formatSpan(duration);
}

function detailOf(row: StepRow, nowMs: number): string {
  const duration = formatSpan((row.end ?? nowMs) - row.start);
  const events = row.listensTo.join(" or ");
  if (row.isGroup && row.attempts.length === 1 && (row.state === "completed" || row.state === "running")) {
    return row.end === undefined
      ? `Runs the tasks inside it. Started ${duration} ago.`
      : `Ran the tasks inside it. Finished after ${duration}.`;
  }
  if (row.state === "waiting") return events ? `Still waiting for ${events}.` : `Still waiting, for ${duration} so far.`;
  if (row.state === "scheduled") return `Waiting for a worker to start it, for ${duration} so far.`;
  if (row.attempts.length > 1 || row.state === "retrying") {
    const sentences = row.attempts.map((attempt, index) => {
      const span = attempt.end !== undefined ? formatSpan(attempt.end - attempt.start) : "";
      if (attempt.outcome === "failed") {
        const error = readableError(attempt.error);
        return `Attempt ${index + 1} failed${attempt.error ? ` with ${error.headline}` : ""}${span ? ` after ${span}` : ""}.`;
      }
      if (attempt.outcome === "succeeded") return `Attempt ${index + 1} succeeded after ${span}.`;
      if (attempt.outcome === "interrupted") return `Attempt ${index + 1} was interrupted after ${span}, when the instance ended.`;
      return `Attempt ${index + 1} is running.`;
    });
    if (row.state === "retrying") sentences.push("Waiting before the next attempt.");
    return sentences.join(" ");
  }
  if (row.state === "failed") {
    const error = readableError(row.error);
    return `Failed after ${duration}: ${error.headline}.${error.explanation ? ` ${error.explanation}.` : ""}`;
  }
  if (row.state === "running") return `Running for ${duration}.`;
  if (row.state === "interrupted") return `Interrupted after ${duration}: the instance ended before this task finished.`;
  if (events) return `Waited ${duration} for ${events}.`;
  return `Took ${duration}.`;
}

/** Status of the instance in one line: what it does now, or why it failed, and where. */
export function instanceSummary(
  rows: StepRow[],
  status: WorkflowRuntimeStatus,
  error: RawError | undefined,
): { title: string; text: string } {
  if (status === "FAULTED") {
    const failed = [...rows].reverse().find((row) => row.state === "failed" && !row.isGroup);
    const where = failed?.pointer ?? error?.instance;
    return { title: readableError(error ?? failed?.error).headline, text: where ? `at ${where}` : "" };
  }
  if (status === "RUNNING" || status === "PENDING") {
    const active = rows.filter((row) => !row.isGroup && row.state !== "completed" && row.state !== "failed");
    const current = active[active.length - 1];
    if (!current) return { title: status === "PENDING" ? "Waiting to start" : "Running", text: "" };
    let title = `Running ${current.label}`;
    if (current.state === "waiting") {
      title = current.listensTo.length > 0 ? `Waiting for ${current.listensTo.map(shortEventType).join(", ")}` : `Waiting in ${current.label}`;
    } else if (current.state === "retrying") {
      title = `Retrying ${current.label}`;
    } else if (current.state === "scheduled") {
      title = `Waiting to start ${current.label}`;
    }
    return { title, text: `at ${current.pointer}` };
  }
  return { title: "", text: "" };
}

export function jsonText(value: unknown): string {
  return value === undefined ? "" : prettyJson(value);
}
