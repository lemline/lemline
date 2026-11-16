import { parse } from "yaml";

/**
 * Reads what a person needs from a workflow definition source (YAML or JSON):
 * how it starts, and what each task does.
 */

export type WorkflowTrigger =
  | { kind: "manual" }
  | { kind: "event"; eventTypes: string[] }
  | { kind: "schedule"; sentence: string; code?: string };

export interface ParsedWorkflow {
  root?: Record<string, unknown>;
  trigger: WorkflowTrigger;
}

type Json = Record<string, unknown>;

function isObject(value: unknown): value is Json {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function parseWorkflowSource(source?: string): ParsedWorkflow {
  if (!source) return { trigger: { kind: "manual" } };
  let root: unknown;
  try {
    root = parse(source);
  } catch {
    return { trigger: { kind: "manual" } };
  }
  if (!isObject(root)) return { trigger: { kind: "manual" } };
  return { root, trigger: readTrigger(root) };
}

function readTrigger(root: Json): WorkflowTrigger {
  const schedule = root.schedule;
  if (!isObject(schedule)) return { kind: "manual" };

  if (isObject(schedule.on)) {
    const types = eventTypesOf(schedule.on);
    if (types.length > 0) return { kind: "event", eventTypes: types };
  }
  if (typeof schedule.cron === "string") {
    return { kind: "schedule", sentence: "A new instance starts on this cron schedule:", code: schedule.cron };
  }
  if (schedule.every !== undefined) {
    return { kind: "schedule", sentence: `A new instance starts every ${durationText(schedule.every)}.` };
  }
  if (schedule.after !== undefined) {
    return { kind: "schedule", sentence: `A new instance starts ${durationText(schedule.after)} after the previous one ends.` };
  }
  return { kind: "manual" };
}

/** Event types listened to by a listen/schedule consumption strategy: one, any or all. */
function eventTypesOf(strategy: Json): string[] {
  const types: string[] = [];
  const read = (filter: unknown) => {
    if (!isObject(filter)) return;
    const withPart = isObject(filter.with) ? filter.with : filter;
    if (typeof withPart.type === "string") types.push(withPart.type);
  };
  read(strategy.one);
  for (const key of ["any", "all"]) {
    const list = strategy[key];
    if (Array.isArray(list)) list.forEach(read);
  }
  return types;
}

function durationText(value: unknown): string {
  if (typeof value === "string") {
    const match = value.match(/^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$/i);
    if (!match) return value;
    const [, d, h, m, s] = match;
    const parts = [d && `${d} d`, h && `${h} h`, m && `${m} min`, s && `${s} s`].filter(Boolean);
    return parts.join(" ") || value;
  }
  if (isObject(value)) {
    const units: [string, string][] = [
      ["days", "d"],
      ["hours", "h"],
      ["minutes", "min"],
      ["seconds", "s"],
      ["milliseconds", "ms"],
    ];
    const parts = units
      .filter(([key]) => typeof value[key] === "number" && (value[key] as number) > 0)
      .map(([key, unit]) => `${value[key]} ${unit}`);
    return parts.join(" ") || "0 s";
  }
  return String(value);
}

/** "com.example.shipment.created" → "shipment.created"; "…race.started.v1" → "race.started.v1". */
export function shortEventType(type: string): string {
  const parts = type.split(".").filter(Boolean);
  const keep = /^v\d+$/.test(parts[parts.length - 1] ?? "") ? 3 : 2;
  if (parts.length <= keep) return type;
  return parts.slice(-keep).join(".");
}

// A for task also has a do body; the loop determines its kind.
const TASK_KINDS = ["call", "for", "do", "emit", "fork", "listen", "raise", "run", "set", "switch", "try", "wait"];

export interface TaskInfo {
  kind?: string;
  /** "call http", "fork · 2 branches", "listen · shipment.created". */
  typeLabel: string;
  /** Full event types a listen task waits for. */
  listensTo: string[];
}

/** Resolves a task by its JSON pointer ("/do/0/callApi") in the parsed definition. */
export function taskAt(root: Json | undefined, pointer: string): TaskInfo {
  const empty: TaskInfo = { typeLabel: "", listensTo: [] };
  if (!root || !pointer.startsWith("/")) return empty;
  let node: unknown = root;
  const parts = pointer.split("/").slice(1);
  for (let index = 0; index < parts.length; index++) {
    const segment = parts[index].replace(/~1/g, "/").replace(/~0/g, "~");
    if (Array.isArray(node)) {
      node = node[Number(segment)];
    } else if (isObject(node)) {
      // A loop's /do is an internal block; a declared do task keeps its own identity.
      if (segment === "do" && index === parts.length - 1 && TASK_KINDS.some((key) => key in (node as Json))) {
        if ("for" in node) node = { do: node.do };
        break;
      }
      node = node[segment];
    } else {
      return empty;
    }
  }
  if (!isObject(node)) return empty;
  const kind = TASK_KINDS.find((key) => key in node);
  if (!kind) return empty;
  const body = node[kind];

  switch (kind) {
    case "call":
      return { kind, typeLabel: typeof body === "string" ? `call ${body}` : "call", listensTo: [] };
    case "fork": {
      const branches = isObject(body) && Array.isArray(body.branches) ? body.branches.length : 0;
      return { kind, typeLabel: branches > 0 ? `fork · ${branches} branches` : "fork", listensTo: [] };
    }
    case "listen": {
      const to = isObject(body) && isObject(body.to) ? eventTypesOf(body.to) : [];
      return {
        kind,
        typeLabel: to.length > 0 ? `listen · ${to.map(shortEventType).join(", ")}` : "listen",
        listensTo: to,
      };
    }
    case "emit": {
      const type =
        isObject(body) && isObject(body.event) && isObject(body.event.with) && typeof body.event.with.type === "string"
          ? body.event.with.type
          : "";
      return { kind, typeLabel: type ? `emit · ${shortEventType(type)}` : "emit", listensTo: [] };
    }
    case "wait":
      return { kind, typeLabel: `wait · ${durationText(body)}`, listensTo: [] };
    case "run": {
      const how = isObject(body) ? ["container", "script", "shell", "workflow"].find((key) => key in body) : undefined;
      return { kind, typeLabel: how ? `run · ${how}` : "run", listensTo: [] };
    }
    default:
      return { kind, typeLabel: kind, listensTo: [] };
  }
}

/** Names of the tasks along a pointer: "/do/2/a/fork/branches/0/b" → ["a", "b"]. */
export function taskNamesOf(pointer: string): string[] {
  const segments = pointer.split("/").slice(1);
  const names: string[] = [];
  for (let index = 1; index < segments.length; index += 1) {
    if (/^\d+$/.test(segments[index - 1]) && !/^\d+$/.test(segments[index])) {
      names.push(segments[index].replace(/~1/g, "/").replace(/~0/g, "~"));
    }
  }
  return names;
}

/** True when the pointer ends on a named task ("/do/0/callApi"), not on a block inside it ("/do/0/a/try"). */
export function isTaskPointer(pointer: string, root?: Json): boolean {
  const segments = pointer.split("/").slice(1);
  if (segments.at(-1) === "do" && /^\d+$/.test(segments.at(-3) ?? "")) {
    if (taskAt(root, pointer.slice(0, -3)).kind === "for") return false;
    segments.pop();
  }
  if (segments.length < 2) return false;
  return /^\d+$/.test(segments[segments.length - 2]) && !/^\d+$/.test(segments[segments.length - 1]);
}

/** An example input matching how the workflow starts. */
export function exampleInput(trigger: WorkflowTrigger): string {
  if (trigger.kind === "event") {
    const type = trigger.eventTypes[0] ?? "";
    return `[\n  {\n    "type": "${type}",\n    "data": {}\n  }\n]`;
  }
  return "{}";
}
