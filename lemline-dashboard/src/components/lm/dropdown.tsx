import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { cn } from "../../lib/utils";
import { ChevronDownIcon } from "./icons";

export interface DropdownOption<T extends string> {
  value: T;
  label: ReactNode;
}

interface DropdownProps<T extends string> {
  /** Muted word before the value: "Workflow", "Version", "Period". */
  label?: string;
  /** Accessible name of the list. */
  listLabel: string;
  value: T;
  valueLabel: ReactNode;
  options: DropdownOption<T>[];
  onChange: (value: T) => void;
  disabled?: boolean;
  /** Accent border when a filter is active. */
  active?: boolean;
  /** Opens upward (pagination footer). */
  up?: boolean;
  /** Sidebar style: full width, bold value, no muted label. */
  block?: boolean;
  small?: boolean;
  /** Full width, chevron pushed to the right (namespace bar on phones). */
  stretch?: boolean;
  className?: string;
}

export function Dropdown<T extends string>({
  label,
  listLabel,
  value,
  valueLabel,
  options,
  onChange,
  disabled,
  active,
  up,
  block,
  small,
  stretch,
  className,
}: DropdownProps<T>) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const listId = useId();

  useEffect(() => {
    if (!open) return;
    const onPointer = (event: MouseEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  return (
    <div ref={rootRef} className={cn("relative", (block || stretch) && "w-full", className)}>
      <button
        type="button"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        disabled={disabled}
        onClick={() => setOpen((previous) => !previous)}
        className={cn(
          "flex items-center gap-2 border bg-lm-panel text-sm text-lm-muted",
          block
            ? "min-h-11 w-full rounded-[10px] border-[1.5px] px-3 text-left text-[15px] font-semibold text-lm-text"
            : "rounded-lg px-3",
          !block && (small ? "min-h-9 px-2.5" : "min-h-11"),
          active ? "border-lm-accent" : block ? "border-lm-line" : "border-lm-line-strong",
          disabled ? "cursor-default opacity-55" : "cursor-pointer",
          stretch && "w-full",
        )}
      >
        {label && <span>{label}</span>}
        {block ? (
          <span className="min-w-0 grow truncate">{valueLabel}</span>
        ) : (
          <strong className="max-w-[260px] truncate whitespace-nowrap font-bold text-lm-text">{valueLabel}</strong>
        )}
        <ChevronDownIcon className={cn("shrink-0", block && "text-lm-muted", stretch && "ml-auto")} />
      </button>
      {open && (
        <div
          id={listId}
          role="listbox"
          aria-label={listLabel}
          className={cn(
            "absolute left-0 z-40 flex min-w-full flex-col gap-0.5 rounded-[10px] border border-lm-line bg-lm-panel p-1.5 shadow-lm-pop",
            up ? "bottom-[calc(100%+6px)]" : "top-[calc(100%+6px)]",
            block ? "right-0" : "min-w-[220px]",
          )}
        >
          {options.map((option) => {
            const selected = option.value === value;
            return (
              <button
                key={option.value}
                type="button"
                role="option"
                aria-selected={selected}
                onClick={() => {
                  setOpen(false);
                  onChange(option.value);
                }}
                className={cn(
                  "min-h-10 cursor-pointer whitespace-nowrap rounded-md border-0 px-3 text-left text-sm text-lm-text",
                  small && "min-h-9",
                  selected ? "bg-lm-accent-soft font-semibold" : "bg-lm-panel font-normal hover:bg-lm-subtle",
                )}
              >
                {option.label}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
