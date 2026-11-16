import type { Duration, Timestamp } from "@bufbuild/protobuf/wkt";
import { durationToMilliseconds, timestampToMilliseconds } from "./proto";

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "01a1d738…da2f": readable start and end of an instance ID. */
export function shortInstanceId(id: string): string {
  if (id.length <= 14) return id;
  return `${id.slice(0, 8)}…${id.slice(-4)}`;
}

function pad(value: number, size = 2): string {
  return String(value).padStart(size, "0");
}

function toMs(value?: Timestamp | string | number): number | undefined {
  if (value === undefined) return undefined;
  if (typeof value === "number") return value;
  return timestampToMilliseconds(value);
}

/** "just now", "3 min ago", "2 h ago", "4 d ago". */
export function formatRelative(value?: Timestamp | string | number, nowMs = Date.now()): string {
  const ms = toMs(value);
  if (ms === undefined) return "—";
  const seconds = Math.max(0, Math.round((nowMs - ms) / 1000));
  if (seconds < 45) return "just now";
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  const days = Math.floor(hours / 24);
  return `${days} d ago`;
}

function sameDay(left: Date, right: Date): boolean {
  return (
    left.getFullYear() === right.getFullYear() &&
    left.getMonth() === right.getMonth() &&
    left.getDate() === right.getDate()
  );
}

/** "Today, 09:38", "Yesterday, 18:02", "Nov 3, 07:41". */
export function formatDayClock(value?: Timestamp | string | number, nowMs = Date.now()): string {
  const ms = toMs(value);
  if (ms === undefined) return "—";
  const date = new Date(ms);
  const now = new Date(nowMs);
  const yesterday = new Date(nowMs - 24 * 3600 * 1000);
  const clock = `${pad(date.getHours())}:${pad(date.getMinutes())}`;
  if (sameDay(date, now)) return `Today, ${clock}`;
  if (sameDay(date, yesterday)) return `Yesterday, ${clock}`;
  const day = `${MONTHS[date.getMonth()]} ${date.getDate()}`;
  return date.getFullYear() === now.getFullYear() ? `${day}, ${clock}` : `${day} ${date.getFullYear()}, ${clock}`;
}

/** "Nov 15, 09:38:02.1": event clock with a tenth of a second. */
export function formatEventClock(value?: string | number): string {
  const ms = toMs(value);
  if (ms === undefined) return "—";
  const date = new Date(ms);
  const tenths = Math.floor(date.getMilliseconds() / 100);
  return `${MONTHS[date.getMonth()]} ${date.getDate()}, ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${tenths}`;
}

/** "2026-11-15 09:38:02.100 UTC". */
export function formatUtc(value?: string | number): string {
  const ms = toMs(value);
  if (ms === undefined) return "—";
  return `${new Date(ms).toISOString().replace("T", " ").replace("Z", "")} UTC`;
}

/** Full local date and time, for tooltips. */
export function formatExact(value?: Timestamp | string | number): string {
  const ms = toMs(value);
  if (ms === undefined) return "";
  return new Date(ms).toLocaleString();
}

/** "0.5 s", "5 s", "2 min 29 s", "3 h 12 min". */
export function formatSpan(ms?: number): string {
  if (ms === undefined || !Number.isFinite(ms) || ms < 0) return "—";
  if (ms < 1_000) return `${Math.round(ms)} ms`;
  if (ms < 10_000) {
    const tenths = Math.round(ms / 100) / 10;
    return `${tenths} s`;
  }
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${seconds} s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} min ${seconds % 60} s`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} h ${minutes % 60} min`;
  const days = Math.floor(hours / 24);
  return `${days} d ${hours % 24} h`;
}

export function formatProtoDuration(value?: Duration): string {
  const ms = durationToMilliseconds(value);
  return ms === undefined || ms <= 0 ? "—" : formatSpan(ms);
}

/** Axis label for an elapsed time: "0 s", "15 s", "2:28", "1:02:10". */
export function formatElapsedTick(ms: number): string {
  const totalSeconds = Math.round(ms / 1000);
  if (ms > 0 && ms < 1000) return `${Math.round(ms)} ms`;
  if (ms > 0 && ms < 10_000 && ms % 1000 !== 0) return `${Math.round(ms / 100) / 10} s`;
  if (totalSeconds < 60) return `${totalSeconds} s`;
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${minutes}:${pad(seconds)}`;
}

export function pluralize(count: number, singular: string, plural = `${singular}s`): string {
  return `${count} ${count === 1 ? singular : plural}`;
}

export function compareVersionsDesc(left: string, right: string): number {
  return right.localeCompare(left, undefined, { numeric: true, sensitivity: "base" });
}

export function prettyJson(value: unknown): string {
  if (value === undefined) return "";
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}
