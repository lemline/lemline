import { useMemo } from "react";
import { Copy } from "lucide-react";
import { toast } from "sonner";
import { useParams } from "react-router-dom";
import { useWatchWorkflowProjection } from "../hooks/use-watch-workflow-projection";
import { Card } from "../components/ui/card";
import { Button } from "../components/ui/button";
import { StatusBadge } from "../components/instances/status-badge";
import { LiveIndicator } from "../components/instances/live-indicator";
import { GanttChart } from "../components/timeline/gantt-chart";
import { EventLog } from "../components/timeline/event-log";
import { GanttSkeleton } from "../components/timeline/gantt-skeleton";
import { EventLogSkeleton } from "../components/timeline/event-log-skeleton";
import { ErrorPanel } from "../components/workflow/error-panel";
import { formatDuration, shortId } from "../lib/utils";
import { buildPageTitle, useDocumentMeta, type FaviconTone } from "../hooks/use-document-meta";
import { useViewportWidth } from "../hooks/use-viewport-width";

export function InstanceDetailPage() {
  const { workflowId } = useParams();
  const timeline = useWatchWorkflowProjection(workflowId);
  const timelineStatus = timeline.data?.status;
  const liveConnected = timeline.liveConnected;
  const viewportWidth = useViewportWidth();
  const showGantt = viewportWidth >= 1024;
  const pageTitle = useMemo(() => {
    const name = timeline.data?.name;
    return buildPageTitle([workflowId ? shortId(workflowId) : "Instance", name]);
  }, [timeline.data?.name, workflowId]);
  const faviconTone = useMemo<FaviconTone>(() => {
    if (timelineStatus === "FAULTED") return "faulted";
    if (timelineStatus === "COMPLETED") return "completed";
    if (timelineStatus === "PENDING") return "pending";
    if (timelineStatus === "RUNNING") return liveConnected ? "running" : "disconnected";
    return "default";
  }, [liveConnected, timelineStatus]);

  useDocumentMeta(pageTitle, faviconTone);

  const durationMs = useMemo(() => {
    const events = timeline.data?.events ?? [];
    const started = events.find((event) => event.eventType.includes("workflow.started"));
    const completed =
      lastMatching(events, (event) => event.eventType.includes("workflow.completed")) ||
      lastMatching(events, (event) => event.eventType.includes("workflow.faulted"));

    if (!started?.eventTime || !completed?.eventTime) return undefined;
    return new Date(completed.eventTime).getTime() - new Date(started.eventTime).getTime();
  }, [timeline.data?.events]);

  const error = useMemo(() => {
    const faulted = lastMatching(
      timeline.data?.events ?? [],
      (event) => event.eventType.includes("workflow.faulted"),
    );
    if (!faulted) return null;
    try {
      const payload = JSON.parse(faulted.payloadJson);
      const details = payload?.data?.error;
      return {
        title: String(details?.title ?? "Workflow faulted"),
        type: String(details?.type ?? "about:blank"),
        status: Number(details?.status ?? 0),
        payloadJson: JSON.stringify(payload, null, 2),
      };
    } catch {
      return {
        title: "Workflow faulted",
        type: "about:blank",
        status: 0,
        payloadJson: faulted.payloadJson,
      };
    }
  }, [timeline.data?.events]);

  if (timeline.isLoading && !timeline.data) {
    return (
      <div className="space-y-4">
        <HeaderSkeleton />
        {showGantt && <GanttSkeleton />}
        <EventLogSkeleton />
      </div>
    );
  }

  const timelineData = timeline.data;

  if ((timeline.isError && !timelineData) || !timelineData) {
    return (
      <Card>
        <p className="text-sm text-red-600 dark:text-red-300">Could not load timeline.</p>
        <Button className="mt-3" variant="outline" onClick={() => timeline.reconnect()}>
          Retry
        </Button>
      </Card>
    );
  }

  return (
    <div className="fade-in-200 space-y-4">
      <Card>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-3">
            <StatusBadge status={timelineData.status} />
            <p className="text-sm text-slate-500">Duration: {formatDuration(durationMs ?? undefined)}</p>
            <p className="text-sm text-slate-500">{timelineData.workflowId}</p>
            <button
              type="button"
              className="text-slate-500 hover:text-slate-900 dark:hover:text-slate-100"
              aria-label="Copy workflow ID"
              onClick={async () => {
                try {
                  await navigator.clipboard.writeText(timelineData.workflowId);
                  toast.success("Copied to clipboard", { duration: 2000 });
                } catch {
                  toast.error("Unable to copy workflow ID");
                }
              }}
            >
              <Copy className="h-4 w-4" />
            </button>
          </div>
          <LiveIndicator live={timelineStatus === "RUNNING" && liveConnected} />
        </div>
      </Card>

      {error && (
        <ErrorPanel title={error.title} type={error.type} status={error.status} payloadJson={error.payloadJson} />
      )}

      {showGantt && <GanttChart events={timelineData.events} />}
      <EventLog events={timelineData.events} />
    </div>
  );
}

function HeaderSkeleton() {
  return (
    <Card role="status" aria-label="Loading instance header">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-3">
          <span className="h-6 w-24 animate-pulse rounded-full bg-slate-200 dark:bg-slate-800" />
          <span className="h-4 w-36 animate-pulse rounded bg-slate-200 dark:bg-slate-800" />
          <span className="h-4 w-52 animate-pulse rounded bg-slate-200 dark:bg-slate-800" />
        </div>
        <span className="h-4 w-20 animate-pulse rounded bg-slate-200 dark:bg-slate-800" />
      </div>
    </Card>
  );
}

function lastMatching<T>(items: T[], predicate: (item: T) => boolean): T | undefined {
  for (let index = items.length - 1; index >= 0; index -= 1) {
    if (predicate(items[index])) return items[index];
  }
  return undefined;
}
