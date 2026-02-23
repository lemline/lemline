import { useEffect, useMemo, useRef, useState } from "react";
import { ArrowDown, ArrowUp, ArrowUpDown, Check, Copy } from "lucide-react";
import { toast } from "sonner";
import { Button } from "../ui/button";
import { Card } from "../ui/card";
import { StatusBadge } from "./status-badge";
import { asNumber, cn, formatDuration, shortId } from "../../lib/utils";

interface InstanceRow {
  workflowId: string;
  namespace: string;
  name: string;
  version: string;
  status: string;
  startedAt?: string;
  durationMs?: bigint | number | string;
  error?: {
    title: string;
  };
}

interface InstanceTableProps {
  rows: InstanceRow[];
  showNamespace?: boolean;
  showName?: boolean;
  onRowClick: (workflowId: string) => void;
  hasMore: boolean;
  isLoadingMore: boolean;
  onLoadMore: () => void;
  totalCount?: bigint | number | string;
}

type SortKey = "startedAt" | "durationMs";
type SortDirection = "asc" | "desc";

export function InstanceTable({
  rows,
  showNamespace = false,
  showName = true,
  onRowClick,
  hasMore,
  isLoadingMore,
  onLoadMore,
  totalCount,
}: InstanceTableProps) {
  const [sortKey, setSortKey] = useState<SortKey>("startedAt");
  const [sortDirection, setSortDirection] = useState<SortDirection>("desc");
  const [copiedWorkflowId, setCopiedWorkflowId] = useState<string | null>(null);
  const copyTimerRef = useRef<number | null>(null);

  useEffect(() => {
    return () => {
      if (copyTimerRef.current != null) {
        window.clearTimeout(copyTimerRef.current);
      }
    };
  }, []);

  const sortedRows = useMemo(() => {
    return [...rows].sort((left, right) => {
      let comparison = 0;
      if (sortKey === "startedAt") {
        comparison = compareNullableNumbers(toTimestamp(left.startedAt), toTimestamp(right.startedAt));
      } else {
        comparison = compareNullableNumbers(toDuration(left.durationMs), toDuration(right.durationMs));
      }

      if (comparison === 0) {
        comparison = left.workflowId.localeCompare(right.workflowId);
      }

      return sortDirection === "asc" ? comparison : -comparison;
    });
  }, [rows, sortDirection, sortKey]);

  const remainingCount =
    totalCount === undefined ? undefined : Math.max(asNumber(totalCount) - sortedRows.length, 0);
  const columnCount = 6 + (showNamespace ? 1 : 0) + (showName ? 1 : 0);

  const setSort = (next: SortKey) => {
    if (sortKey === next) {
      setSortDirection((current) => (current === "asc" ? "desc" : "asc"));
      return;
    }
    setSortKey(next);
    setSortDirection("desc");
  };

  const copyId = async (value: string) => {
    try {
      await navigator.clipboard.writeText(value);
      setCopiedWorkflowId(value);
      if (copyTimerRef.current != null) {
        window.clearTimeout(copyTimerRef.current);
      }
      copyTimerRef.current = window.setTimeout(() => {
        setCopiedWorkflowId((current) => (current === value ? null : current));
      }, 300);
      toast.success("Copied to clipboard", { duration: 2000 });
    } catch {
      toast.error("Unable to copy workflow ID");
    }
  };

  return (
    <Card className="overflow-hidden p-0">
      <table className="w-full text-sm">
        <thead className="bg-slate-50 text-left text-slate-500 dark:bg-slate-900 dark:text-slate-400">
          <tr>
            <th className="w-[200px] max-w-[200px] px-4 py-2">Workflow ID</th>
            {showNamespace && <th className="w-[170px] px-4 py-2">Namespace</th>}
            {showName && <th className="w-[230px] px-4 py-2">Name</th>}
            <th className="w-[100px] px-4 py-2">Version</th>
            <th className="w-[90px] px-4 py-2">Status</th>
            <th className="max-w-[300px] px-4 py-2">Error</th>
            <th className="w-[210px] px-4 py-2">
              <SortButton
                label="Started"
                isActive={sortKey === "startedAt"}
                direction={sortDirection}
                onClick={() => setSort("startedAt")}
              />
            </th>
            <th className="w-[120px] px-4 py-2">
              <SortButton
                label="Duration"
                isActive={sortKey === "durationMs"}
                direction={sortDirection}
                onClick={() => setSort("durationMs")}
              />
            </th>
          </tr>
        </thead>
        <tbody>
          {sortedRows.map((row) => (
            <tr
              key={row.workflowId}
              className="group cursor-pointer border-t border-slate-200 hover:bg-slate-50 dark:border-slate-800 dark:hover:bg-slate-900"
              onClick={() => onRowClick(row.workflowId)}
            >
              <td className="w-[200px] max-w-[200px] px-4 py-2">
                <div className="flex min-w-0 items-center gap-2">
                  <span className="truncate font-mono text-[13px]" title={row.workflowId}>
                    {shortId(row.workflowId, 10, 6)}
                  </span>
                  <button
                    type="button"
                    onClick={(event) => {
                      event.stopPropagation();
                      void copyId(row.workflowId);
                    }}
                    className={cn(
                      "shrink-0 text-slate-400 opacity-0 transition hover:text-slate-700 focus:opacity-100 group-hover:opacity-100 dark:hover:text-slate-100",
                      copiedWorkflowId === row.workflowId && "opacity-100 text-emerald-600 dark:text-emerald-400",
                    )}
                    aria-label="Copy workflow ID"
                  >
                    {copiedWorkflowId === row.workflowId ? (
                      <Check className="h-3.5 w-3.5" />
                    ) : (
                      <Copy className="h-3.5 w-3.5" />
                    )}
                  </button>
                </div>
              </td>
              {showNamespace && <td className="w-[170px] max-w-[170px] truncate px-4 py-2">{row.namespace}</td>}
              {showName && <td className="w-[230px] max-w-[230px] truncate px-4 py-2">{row.name}</td>}
              <td className="w-[100px] px-4 py-2">{row.version}</td>
              <td className="w-[90px] px-4 py-2">
                <StatusBadge status={row.status} />
              </td>
              <td className="max-w-[300px] truncate px-4 py-2" title={row.error?.title ?? ""}>
                {row.error?.title ?? "-"}
              </td>
              <td className="w-[210px] px-4 py-2 tabular-nums">
                {row.startedAt ? new Date(row.startedAt).toLocaleString() : "-"}
              </td>
              <td className="w-[120px] px-4 py-2 tabular-nums">{formatDuration(row.durationMs)}</td>
            </tr>
          ))}
          {sortedRows.length === 0 && (
            <tr>
              <td colSpan={columnCount} className="px-4 py-6 text-slate-500">
                No instances found for this scope.
              </td>
            </tr>
          )}
        </tbody>
      </table>
      {hasMore && (
        <div className="border-t border-slate-200 p-3 text-center dark:border-slate-800">
          <Button variant="outline" onClick={onLoadMore} disabled={isLoadingMore}>
            {isLoadingMore
              ? "Loading..."
              : remainingCount !== undefined && remainingCount > 0
                ? `Load more (${remainingCount.toLocaleString()} remaining)`
                : "Load more"}
          </Button>
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
}

function SortButton({ label, isActive, direction, onClick }: SortButtonProps) {
  return (
    <button
      type="button"
      className="inline-flex items-center gap-1 text-xs font-semibold uppercase tracking-wide text-slate-500 transition hover:text-slate-900 dark:text-slate-400 dark:hover:text-slate-200"
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

function toTimestamp(value: string | undefined): number | null {
  if (!value) return null;
  const timestamp = Date.parse(value);
  return Number.isNaN(timestamp) ? null : timestamp;
}

function toDuration(value: bigint | number | string | undefined): number | null {
  if (value === undefined) return null;
  return asNumber(value);
}

function compareNullableNumbers(left: number | null, right: number | null): number {
  if (left === null && right === null) return 0;
  if (left === null) return 1;
  if (right === null) return -1;
  return left - right;
}
