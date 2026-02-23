import { cn } from "../../lib/utils";

interface TabOption {
  value: string;
  label: string;
}

interface TabsProps {
  value: string;
  onValueChange: (value: string) => void;
  options: TabOption[];
}

export function Tabs({ value, onValueChange, options }: TabsProps) {
  return (
    <div className="inline-flex rounded-md border border-slate-300 bg-white p-1 dark:border-slate-700 dark:bg-slate-900">
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          onClick={() => onValueChange(option.value)}
          className={cn(
            "rounded px-3 py-1.5 text-sm",
            value === option.value
              ? "bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900"
              : "text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-800",
          )}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}
