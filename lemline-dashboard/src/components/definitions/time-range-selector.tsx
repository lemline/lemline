import { Select } from "../ui/select";
import type { TimeRangePreset } from "../../lib/time-range";

interface TimeRangeSelectorProps {
  value: TimeRangePreset;
  onChange: (next: TimeRangePreset) => void;
}

export function TimeRangeSelector({ value, onChange }: TimeRangeSelectorProps) {
  return (
    <div className="flex items-center gap-3">
      <p className="text-sm font-medium text-slate-700 dark:text-slate-300">Time range</p>
      <div className="w-48">
        <Select
          value={value}
          onChange={(event) => onChange(event.target.value as TimeRangePreset)}
          options={[
            { value: "1h", label: "Last 1 hour" },
            { value: "24h", label: "Last 24 hours" },
            { value: "7d", label: "Last 7 days" },
            { value: "30d", label: "Last 30 days" },
            { value: "all", label: "All time" },
          ]}
        />
      </div>
    </div>
  );
}
