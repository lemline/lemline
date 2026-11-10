import { subHours, subDays } from "date-fns";

export type TimeRangePreset = "1h" | "24h" | "7d" | "30d" | "all";

// Presets are "since" windows with no upper bound: an upper bound fixed when the page opens
// would make the live instance table and counters ignore every instance created afterwards.
export interface TimeWindow {
  from?: string;
}

export function toTimeWindow(range: TimeRangePreset): TimeWindow {
  const now = new Date();

  switch (range) {
    case "1h":
      return { from: subHours(now, 1).toISOString() };
    case "24h":
      return { from: subHours(now, 24).toISOString() };
    case "7d":
      return { from: subDays(now, 7).toISOString() };
    case "30d":
      return { from: subDays(now, 30).toISOString() };
    case "all":
      return {};
  }
}
