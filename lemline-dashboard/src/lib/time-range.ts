import { subHours, subDays } from "date-fns";

export type TimeRangePreset = "1h" | "24h" | "7d" | "30d" | "all";

export interface TimeWindow {
  from?: string;
  to?: string;
}

export function toTimeWindow(range: TimeRangePreset): TimeWindow {
  const now = new Date();

  switch (range) {
    case "1h":
      return { from: subHours(now, 1).toISOString(), to: now.toISOString() };
    case "24h":
      return { from: subHours(now, 24).toISOString(), to: now.toISOString() };
    case "7d":
      return { from: subDays(now, 7).toISOString(), to: now.toISOString() };
    case "30d":
      return { from: subDays(now, 30).toISOString(), to: now.toISOString() };
    case "all":
      return {};
  }
}
