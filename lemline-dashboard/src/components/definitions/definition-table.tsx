import { type CSSProperties, useMemo, useRef, useState } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { ArrowDown, ArrowUp, ArrowUpDown, ChevronRight } from "lucide-react";
import { Card } from "../ui/card";
import { asNumber, cn } from "../../lib/utils";

export interface BreakdownRow {
  key: string;
  label: string;
  latestVersion?: string;
  started: bigint | number | string;
  completed: bigint | number | string;
  faulted: bigint | number | string;
  running: bigint | number | string;
}

interface DefinitionTableProps {
  title: string;
  rows: BreakdownRow[];
  onRowClick?: (row: BreakdownRow) => void;
  labelColumnTitle?: string;
  showLatestVersionColumn?: boolean;
  latestVersionColumnTitle?: string;
  defaultSortKey?: SortKey;
  defaultSortDirection?: SortDirection;
}

type SortKey = "label" | "started" | "completed" | "faulted" | "running";
type SortDirection = "asc" | "desc";

const VIRTUALIZATION_THRESHOLD = 50;

const GRID_COLUMNS_DEFAULT = "grid-cols-[minmax(220px,1fr)_110px_110px_110px_110px]";
const GRID_COLUMNS_WITH_LATEST_VERSION =
  "grid-cols-[minmax(180px,1fr)_150px_110px_110px_110px_110px]";

export function DefinitionTable({
  title,
  rows,
  onRowClick,
  labelColumnTitle = "Name",
  showLatestVersionColumn = false,
  latestVersionColumnTitle = "Latest Version",
  defaultSortKey = "label",
  defaultSortDirection = "asc",
}: DefinitionTableProps) {
  const [sortKey, setSortKey] = useState<SortKey>(defaultSortKey);
  const [sortDirection, setSortDirection] = useState<SortDirection>(defaultSortDirection);
  const parentRef = useRef<HTMLDivElement | null>(null);

  const sortedRows = useMemo(() => {
    return [...rows].sort((left, right) => {
      let comparison = 0;
      if (sortKey === "label") {
        comparison = left.label.localeCompare(right.label, undefined, {
          numeric: true,
          sensitivity: "base",
        });
      } else {
        comparison = asNumber(left[sortKey]) - asNumber(right[sortKey]);
      }

      if (comparison === 0) {
        comparison = left.label.localeCompare(right.label, undefined, {
          numeric: true,
          sensitivity: "base",
        });
      }

      return sortDirection === "asc" ? comparison : -comparison;
    });
  }, [rows, sortDirection, sortKey]);

  // eslint-disable-next-line react-hooks/incompatible-library
  const virtualizer = useVirtualizer({
    count: sortedRows.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => 42,
    overscan: 10,
  });

  const shouldVirtualize = sortedRows.length > VIRTUALIZATION_THRESHOLD;
  const gridColumns = showLatestVersionColumn ? GRID_COLUMNS_WITH_LATEST_VERSION : GRID_COLUMNS_DEFAULT;

  const setSort = (nextKey: SortKey) => {
    if (sortKey === nextKey) {
      setSortDirection((current) => (current === "asc" ? "desc" : "asc"));
      return;
    }
    setSortKey(nextKey);
    setSortDirection(nextKey === "label" ? "asc" : "desc");
  };

  const virtualRows = shouldVirtualize ? virtualizer.getVirtualItems() : [];

  return (
    <Card className="overflow-hidden p-0">
      <div className="border-b border-slate-200 px-4 py-3 dark:border-slate-800">
        <h3 className="text-sm font-semibold uppercase tracking-wide text-slate-600 dark:text-slate-300">{title}</h3>
      </div>
      <div
        className={cn(
          "grid items-center border-b border-slate-200 bg-slate-50 px-4 py-2 dark:border-slate-800 dark:bg-slate-900",
          gridColumns,
        )}
      >
        <SortButton
          label={labelColumnTitle}
          isActive={sortKey === "label"}
          direction={sortDirection}
          onClick={() => setSort("label")}
          className="justify-start"
        />
        {showLatestVersionColumn && (
          <span className="text-left text-xs font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400">
            {latestVersionColumnTitle}
          </span>
        )}
        <SortButton
          label="Started"
          isActive={sortKey === "started"}
          direction={sortDirection}
          onClick={() => setSort("started")}
          className="justify-end"
        />
        <SortButton
          label="Completed"
          isActive={sortKey === "completed"}
          direction={sortDirection}
          onClick={() => setSort("completed")}
          className="justify-end"
        />
        <SortButton
          label="Faulted"
          isActive={sortKey === "faulted"}
          direction={sortDirection}
          onClick={() => setSort("faulted")}
          className="justify-end"
        />
        <SortButton
          label="Running"
          isActive={sortKey === "running"}
          direction={sortDirection}
          onClick={() => setSort("running")}
          className="justify-end"
        />
      </div>

      {sortedRows.length === 0 && <p className="px-4 py-5 text-sm text-slate-500">No breakdown data for this scope.</p>}

      {sortedRows.length > 0 && !shouldVirtualize && (
        <div>
          {sortedRows.map((row) => (
            <DefinitionRow
              key={row.key}
              row={row}
              onRowClick={onRowClick}
              showLatestVersionColumn={showLatestVersionColumn}
              gridColumns={gridColumns}
            />
          ))}
        </div>
      )}

      {sortedRows.length > 0 && shouldVirtualize && (
        <div ref={parentRef} className="max-h-[460px] overflow-auto">
          <div
            className="relative w-full"
            style={{
              height: `${virtualizer.getTotalSize()}px`,
            }}
          >
            {virtualRows.map((virtualRow) => (
              <DefinitionRow
                key={sortedRows[virtualRow.index].key}
                row={sortedRows[virtualRow.index]}
                onRowClick={onRowClick}
                showLatestVersionColumn={showLatestVersionColumn}
                gridColumns={gridColumns}
                style={{
                  position: "absolute",
                  top: 0,
                  left: 0,
                  width: "100%",
                  transform: `translateY(${virtualRow.start}px)`,
                }}
              />
            ))}
          </div>
        </div>
      )}
    </Card>
  );
}

interface SortButtonProps {
  label: string;
  isActive: boolean;
  direction: SortDirection;
  onClick: () => void;
  className?: string;
}

function SortButton({ label, isActive, direction, onClick, className }: SortButtonProps) {
  return (
    <button
      type="button"
      className={cn(
        "inline-flex items-center gap-1 text-xs font-semibold uppercase tracking-wide text-slate-500 transition hover:text-slate-900 dark:text-slate-400 dark:hover:text-slate-200",
        className,
      )}
      onClick={onClick}
      aria-label={`Sort by ${label}`}
    >
      <span>{label}</span>
      {isActive ? (
        direction === "asc" ? (
          <ArrowUp className="h-3.5 w-3.5" />
        ) : (
          <ArrowDown className="h-3.5 w-3.5" />
        )
      ) : (
        <ArrowUpDown className="h-3.5 w-3.5 opacity-50" />
      )}
    </button>
  );
}

interface DefinitionRowProps {
  row: BreakdownRow;
  onRowClick?: (row: BreakdownRow) => void;
  showLatestVersionColumn: boolean;
  gridColumns: string;
  style?: CSSProperties;
}

function DefinitionRow({ row, onRowClick, showLatestVersionColumn, gridColumns, style }: DefinitionRowProps) {
  const clickable = Boolean(onRowClick);

  return (
    <div
      className={cn(
        "group grid items-center border-t border-slate-200 px-4 py-2 text-sm dark:border-slate-800",
        gridColumns,
        clickable
          ? "cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-900"
          : "hover:bg-slate-50 dark:hover:bg-slate-900",
      )}
      onClick={clickable ? () => onRowClick?.(row) : undefined}
      onKeyDown={
        clickable
          ? (event) => {
              if (event.key !== "Enter" && event.key !== " ") return;
              event.preventDefault();
              onRowClick?.(row);
            }
          : undefined
      }
      role={clickable ? "button" : undefined}
      tabIndex={clickable ? 0 : undefined}
      style={style}
    >
      <div className="flex min-w-0 items-center justify-between gap-2">
        <span
          className={cn(
            "truncate font-medium",
            clickable ? "text-cyan-700 dark:text-cyan-300" : "text-slate-800 dark:text-slate-100",
          )}
        >
          {row.label}
        </span>
        <ChevronRight
          className={cn(
            "h-4 w-4 shrink-0 text-slate-400 transition",
            clickable ? "opacity-0 group-hover:opacity-100 group-focus-within:opacity-100" : "opacity-30",
          )}
        />
      </div>
      {showLatestVersionColumn && (
        <span className="truncate text-left text-slate-600 dark:text-slate-300">
          {row.latestVersion ?? "\u2014"}
        </span>
      )}
      <span className="text-right tabular-nums">{asNumber(row.started).toLocaleString()}</span>
      <span className="text-right tabular-nums">{asNumber(row.completed).toLocaleString()}</span>
      <span className="text-right tabular-nums">{asNumber(row.faulted).toLocaleString()}</span>
      <span className="text-right tabular-nums">{asNumber(row.running).toLocaleString()}</span>
    </div>
  );
}
