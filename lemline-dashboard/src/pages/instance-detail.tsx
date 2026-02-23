import { useEffect, useMemo, useRef, useState } from "react";
import { Copy } from "lucide-react";
import { toast } from "sonner";
import { useParams } from "react-router-dom";
import { WatchWorkflowRequest } from "../gen/proto/lemline/gateway/v1/workflow_gateway_pb";
import { useInstanceTimeline } from "../hooks/use-instance-timeline";
import { gatewayClient } from "../lib/grpc-client";
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

const MAX_STREAM_RETRIES = 5;

export function InstanceDetailPage() {
  const { workflowId } = useParams();
  const timeline = useInstanceTimeline(workflowId, true);
  const timelineStatus = timeline.data?.status;
  const refetchTimeline = timeline.refetch;
  const refreshTimerRef = useRef<number | null>(null);
  const [liveConnected, setLiveConnected] = useState(false);
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

  useEffect(() => {
    if (!workflowId || timelineStatus !== "RUNNING") {
      setLiveConnected(false);
      if (refreshTimerRef.current != null) {
        window.clearTimeout(refreshTimerRef.current);
        refreshTimerRef.current = null;
      }
      return;
    }

    const controller = new AbortController();
    let disposed = false;
    let retries = 0;
    let needsReconnectToast = false;

    const scheduleRefresh = () => {
      if (refreshTimerRef.current != null) return;
      refreshTimerRef.current = window.setTimeout(() => {
        refreshTimerRef.current = null;
        void refetchTimeline();
      }, 250);
    };

    const watch = async () => {
      while (!disposed && !controller.signal.aborted) {
        try {
          const request = new WatchWorkflowRequest({ workflowId });
          setLiveConnected(true);

          for await (const event of gatewayClient.watchWorkflow(request, { signal: controller.signal })) {
            void event;
            if (needsReconnectToast) {
              toast.success("Reconnected — live updates resumed", { duration: 3000 });
              needsReconnectToast = false;
            }
            retries = 0;
            setLiveConnected(true);
            scheduleRefresh();
          }

          if (controller.signal.aborted || disposed) {
            return;
          }

          throw new Error("Workflow stream disconnected");
        } catch {
          if (controller.signal.aborted || disposed) {
            return;
          }

          retries += 1;
          needsReconnectToast = true;
          setLiveConnected(false);

          if (retries > MAX_STREAM_RETRIES) {
            toast.error("Live updates unavailable. Reload page.", { duration: Infinity });
            return;
          }

          toast.error("Connection error — retrying...");
          await delay(backoffDelayMs(retries));
        }
      }
    };

    void watch();

    return () => {
      disposed = true;
      controller.abort();
      setLiveConnected(false);
      if (refreshTimerRef.current != null) {
        window.clearTimeout(refreshTimerRef.current);
        refreshTimerRef.current = null;
      }
    };
  }, [workflowId, timelineStatus, refetchTimeline]);

  if (timeline.isLoading) {
    return (
      <div className="space-y-4">
        <HeaderSkeleton />
        {showGantt && <GanttSkeleton />}
        <EventLogSkeleton />
      </div>
    );
  }

  if (timeline.isError || !timeline.data) {
    return (
      <Card>
        <p className="text-sm text-red-600 dark:text-red-300">Could not load timeline.</p>
        <Button className="mt-3" variant="outline" onClick={() => timeline.refetch()}>
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
            <StatusBadge status={timeline.data.status} />
            <p className="text-sm text-slate-500">Duration: {formatDuration(durationMs ?? undefined)}</p>
            <p className="text-sm text-slate-500">{timeline.data.workflowId}</p>
            <button
              type="button"
              className="text-slate-500 hover:text-slate-900 dark:hover:text-slate-100"
              aria-label="Copy workflow ID"
              onClick={async () => {
                try {
                  await navigator.clipboard.writeText(timeline.data.workflowId);
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

      {showGantt && <GanttChart events={timeline.data.events} />}
      <EventLog events={timeline.data.events} />
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

function backoffDelayMs(retryAttempt: number): number {
  return Math.min(1000 * 2 ** Math.max(0, retryAttempt - 1), 30_000);
}

function delay(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}
