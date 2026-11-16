export type PeriodKey = "1h" | "24h" | "7d" | "30d" | "all";

export const PERIODS: { key: PeriodKey; label: string; ms?: number; phrase: string }[] = [
  { key: "1h", label: "Last hour", ms: 3600_000, phrase: "in the last hour" },
  { key: "24h", label: "Last 24 hours", ms: 24 * 3600_000, phrase: "in the last 24 hours" },
  { key: "7d", label: "Last 7 days", ms: 7 * 24 * 3600_000, phrase: "in the last 7 days" },
  { key: "30d", label: "Last 30 days", ms: 30 * 24 * 3600_000, phrase: "in the last 30 days" },
  { key: "all", label: "All time", phrase: "" },
];

export function isPeriodKey(value: string | null): value is PeriodKey {
  return PERIODS.some((period) => period.key === value);
}

export function periodOf(key: PeriodKey) {
  return PERIODS.find((period) => period.key === key) ?? PERIODS[1];
}

/**
 * Lower bound of a period, rounded down to the minute so that the query key stays
 * stable between renders. No upper bound: new instances keep appearing.
 */
export function periodFrom(key: PeriodKey, nowMs = Date.now()): string | undefined {
  const period = periodOf(key);
  if (period.ms === undefined) return undefined;
  const from = Math.floor((nowMs - period.ms) / 60_000) * 60_000;
  return new Date(from).toISOString();
}
