import { useMemo, useState } from "react";
import { useNow } from "../hooks/use-now";
import { useLocation, useParams } from "react-router-dom";
import { useDefinition } from "../hooks/use-definition";
import { buildPageTitle, useDocumentMeta, type FaviconTone } from "../hooks/use-document-meta";
import { useDashboardNavigation } from "../hooks/use-navigation";
import { useViewportWidth } from "../hooks/use-viewport-width";
import { useWatchWorkflowProjection } from "../hooks/use-watch-workflow-projection";
import { formatDayClock, formatExact, formatRelative, formatSpan, shortInstanceId } from "../lib/format";
import { buildTimeline, instanceSummary, jsonText, workflowBounds } from "../lib/timeline";
import { parseWorkflowSource } from "../lib/workflow-source";
import { CodeBlock } from "../components/lm/code-block";
import { Drawer, DrawerBody, DrawerHeader } from "../components/lm/drawer";
import { ErrorBlock, LoadingBlock } from "../components/lm/empty-state";
import { EventsTable } from "../components/lm/events-table";
import { Gantt } from "../components/lm/gantt";
import { ChevronRightIcon } from "../components/lm/icons";
import { PageHeader, SectionTitle } from "../components/lm/page-header";
import { usePagePadding } from "../hooks/use-layout";
import { STATUS_LABEL, STATUS_TEXT_CLASS, uiStatusOf } from "../lib/status";

export function InstancePage() {
  const { instanceId = "" } = useParams();
  const pagePad = usePagePadding();
  const narrow = useViewportWidth() < 720;
  const { navigate, openDefinition } = useDashboardNavigation();
  const location = useLocation();
  const justStarted = (location.state as { justStarted?: boolean } | null)?.justStarted === true;
  const projection = useWatchWorkflowProjection(instanceId, justStarted);
  const data = projection.data;
  const runtime = data?.status ?? "UNKNOWN";
  const terminal = runtime === "COMPLETED" || runtime === "FAULTED";
  const nowMs = useNow(1000, Boolean(data) && !terminal);
  const [detailOpen, setDetailOpen] = useState(false);

  const definition = useDefinition(data?.namespace, data?.name, data?.version || undefined);
  const root = useMemo(() => parseWorkflowSource(definition.data?.source).root, [definition.data?.source]);

  const events = useMemo(() => data?.events ?? [], [data?.events]);
  const bounds = useMemo(() => workflowBounds(events, runtime, nowMs), [events, nowMs, runtime]);
  const model = useMemo(() => buildTimeline(events, runtime, root, nowMs), [events, nowMs, root, runtime]);
  const error = bounds.error ?? data?.error;
  const summary = instanceSummary(model.rows, runtime, error);
  const status = uiStatusOf(runtime === "UNKNOWN" ? undefined : runtime);

  const faviconTone: FaviconTone = !data
    ? "default"
    : runtime === "FAULTED"
      ? "faulted"
      : runtime === "COMPLETED"
        ? "completed"
        : !projection.liveConnected
          ? "disconnected"
          : runtime === "PENDING"
            ? "pending"
            : "running";
  useDocumentMeta(buildPageTitle([shortInstanceId(instanceId), data?.name]), faviconTone);

  const trail = [{ label: "Instances", onClick: () => navigate("/instances") }];
  const header = <PageHeader title={instanceId} trail={trail} />;

  if (projection.isLoading && !data) {
    return (
      <div className="flex min-h-[760px] w-full flex-col bg-lm-bg">
        {header}
        <div className="flex flex-col gap-8" style={{ padding: pagePad }}>
          {projection.starting && (
            <p role="status" className="m-0 text-[15px] text-lm-muted">
              Starting the instance: it appears here as soon as a worker picks it up.
            </p>
          )}
          <LoadingBlock label={projection.starting ? "Starting the instance" : "Loading the instance"} height={96} />
          <LoadingBlock label="Loading the timeline" height={260} />
        </div>
      </div>
    );
  }

  if (!data) {
    return (
      <div className="flex min-h-[760px] w-full flex-col bg-lm-bg">
        {header}
        <div style={{ padding: pagePad }}>
          <ErrorBlock
            text={
              projection.notVisibleYet
                ? `Instance ${instanceId} was accepted, but Lemline does not show it yet: no worker has started it.`
                : `Instance ${instanceId} could not be loaded.`
            }
            onRetry={projection.reconnect}
          />
        </div>
      </div>
    );
  }

  const startedMs = bounds.startedAt;
  const durationMs = data.durationMs ?? (terminal ? bounds.end - startedMs : undefined);
  const durationText = terminal ? formatSpan(durationMs ?? bounds.end - startedMs) : "Still running";
  const startedText = (
    <>
      {formatDayClock(startedMs, nowMs)} <span className="text-lm-muted">({formatRelative(startedMs, nowMs)})</span>
    </>
  );
  const ns = data.namespace ?? "";
  const wf = data.name ?? "";
  const ver = data.version ?? "";
  const seeDefinition = () => openDefinition(ns, wf, ver || undefined);

  const statusLine = (
    <span className="flex min-w-0 flex-col gap-1">
      <span className="m-0 flex flex-wrap items-center gap-x-2.5 gap-y-1 text-[15px] font-semibold leading-[1.4] text-lm-text">
        <span className={`inline-flex items-center gap-2 ${STATUS_TEXT_CLASS[status]}`}>
          <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true">
            <circle cx="5" cy="5" r="5" fill="currentColor" />
          </svg>
          {runtime === "PENDING" ? "Pending" : STATUS_LABEL[status]}
        </span>
        {summary.title && (
          <>
            <span aria-hidden="true" className="font-normal text-lm-line-strong">
              ·
            </span>
            <span className="[overflow-wrap:anywhere]">{summary.title}</span>
          </>
        )}
      </span>
      {summary.text && (
        <span className="block pl-[18px] text-sm leading-normal text-lm-muted [overflow-wrap:anywhere]">{summary.text}</span>
      )}
    </span>
  );

  const definitionLink = (
    <button
      type="button"
      onClick={seeDefinition}
      className="inline-flex cursor-pointer items-center gap-1 border-0 bg-transparent p-0 font-sans text-sm font-medium text-lm-accent"
    >
      See definition
      <ChevronRightIcon />
    </button>
  );

  return (
    <div className="flex min-h-[760px] w-full flex-col bg-lm-bg">
      {header}
      <div className="flex flex-col gap-8" style={{ padding: pagePad }}>
        <div className="flex flex-col gap-3">
          <h2 className="m-0 flex flex-wrap items-baseline gap-x-2.5 gap-y-1 font-display text-lg font-semibold leading-[1.3] text-lm-title">
            <span className="[overflow-wrap:anywhere]">
              {ns} / {wf}
            </span>
            {ver && <span className="font-sans text-[15px] font-medium text-lm-muted">v{ver}</span>}
            {definitionLink}
          </h2>
          <button
            type="button"
            aria-haspopup="dialog"
            onClick={() => setDetailOpen(true)}
            className="lm-card-hover box-border flex w-full cursor-pointer flex-wrap items-center justify-between gap-x-10 gap-y-4 rounded-[14px] border border-lm-line bg-lm-panel text-left text-lm-text shadow-lm-card"
            style={{ padding: narrow ? 16 : "20px 24px" }}
          >
            {statusLine}
            <span className="flex flex-wrap items-center gap-x-10 gap-y-4">
              <span className="flex min-w-0 flex-col gap-1" title={formatExact(startedMs)}>
                <span className="text-[13px] text-lm-muted">Started</span>
                <span className="text-[15px] tabular-nums">{startedText}</span>
              </span>
              <span className="flex min-w-0 flex-col gap-1">
                <span className="text-[13px] text-lm-muted">Duration</span>
                <span className="text-[15px] tabular-nums">{durationText}</span>
              </span>
              <span className="inline-flex items-center gap-1 text-sm font-medium text-lm-accent">
                Details
                <ChevronRightIcon width={14} height={14} />
              </span>
            </span>
          </button>
        </div>

        <section aria-label="Timeline" className="flex flex-col gap-3">
          <SectionTitle>Timeline</SectionTitle>
          <Gantt model={model} nowMs={nowMs} />
        </section>

        <section aria-label="Events" className="flex flex-col gap-3">
          <SectionTitle>Events</SectionTitle>
          <EventsTable events={events} startMs={startedMs} root={root} />
        </section>
      </div>

      {detailOpen && (
        <Drawer label="Instance details" closeLabel="Close the instance panel" onClose={() => setDetailOpen(false)}>
          {(close) => (
            <>
              <DrawerHeader
                onClose={close}
                title={
                  <h2 className="m-0 font-display text-xl font-semibold text-lm-title [overflow-wrap:anywhere]">
                    {ns} / {wf}
                  </h2>
                }
                subtitle={
                  <p className="m-0 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-lm-muted">
                    {ver && <span>v{ver}</span>}
                    {definitionLink}
                  </p>
                }
              />
              <DrawerBody>
                <div className="flex flex-col gap-1">{statusLine}</div>
                <dl className="m-0 grid grid-cols-[max-content_minmax(0,1fr)] gap-x-5 gap-y-2.5 text-sm">
                  <dt className="text-lm-muted">Instance</dt>
                  <dd className="m-0 font-mono text-[13px] [overflow-wrap:anywhere]">{instanceId}</dd>
                  <dt className="text-lm-muted">Started</dt>
                  <dd className="m-0">{startedText}</dd>
                  <dt className="text-lm-muted">Duration</dt>
                  <dd className="m-0 tabular-nums">{durationText}</dd>
                </dl>
                <IoSection title="Input" json={bounds.input !== undefined ? jsonText(bounds.input) : ""} empty="No input recorded." />
                {bounds.hasOutput ? (
                  <IoSection title="Output" json={jsonText(bounds.output)} empty="" />
                ) : runtime === "FAULTED" ? (
                  <IoEmpty title="Output" tone="fail" text="No output: the instance faulted before the end." />
                ) : (
                  <IoEmpty title="Output" tone="run" text="Not available yet: the output appears when the instance completes." />
                )}
                {runtime === "FAULTED" && error && <IoSection title="Error" json={jsonText(error)} empty="" error />}
              </DrawerBody>
            </>
          )}
        </Drawer>
      )}
    </div>
  );
}

function IoSection({ title, json, empty, error }: { title: string; json: string; empty: string; error?: boolean }) {
  return (
    <section aria-label={title} className="flex flex-col gap-2.5">
      <h3 className={`m-0 text-[15px] font-bold ${error ? "text-lm-fail" : ""}`}>{title}</h3>
      {json ? <CodeBlock text={json} /> : <p className="m-0 text-[15px] text-lm-muted">{empty || "—"}</p>}
    </section>
  );
}

function IoEmpty({ title, text, tone }: { title: string; text: string; tone: "fail" | "run" }) {
  return (
    <section aria-label={title} className="flex flex-col gap-2.5">
      <h3 className="m-0 text-[15px] font-bold">{title}</h3>
      <div className="flex min-h-16 items-center gap-3 rounded-[10px] border border-lm-line bg-lm-subtle px-[18px] py-3.5">
        <span
          aria-hidden="true"
          className={`h-2.5 w-2.5 shrink-0 rounded-full ${tone === "fail" ? "bg-lm-fail" : "bg-lm-run-bar"}`}
        />
        <span className={`text-sm leading-normal ${tone === "fail" ? "text-lm-fail-strong" : "text-lm-muted"}`}>{text}</span>
      </div>
    </section>
  );
}
