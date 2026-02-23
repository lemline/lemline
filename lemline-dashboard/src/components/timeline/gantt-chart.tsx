import { Maximize2, Minus, Plus, X } from "lucide-react";
import { useEffect, useId, useMemo, useRef, useState } from "react";
import { Button } from "../ui/button";
import { Card } from "../ui/card";
import { formatDuration } from "../../lib/utils";

interface TimelineEventLike {
  sequence: bigint | number | string;
  eventType: string;
  eventTime: string;
  category: string;
  taskPointer?: string;
  taskName?: string;
  payloadJson: string;
}

interface GanttChartProps {
  events: TimelineEventLike[];
}

type AttemptStatus = "pending" | "running" | "completed" | "faulted" | "retried";

interface NormalizedTimelineEvent extends TimelineEventLike {
  timeMs: number;
  sequenceBigInt: bigint;
}

interface TimelineAttempt {
  id: string;
  pointer: string;
  label: string;
  retryIndex: number;
  status: AttemptStatus;
  startMs: number;
  startedMs?: number;
  endMs: number;
  events: NormalizedTimelineEvent[];
}

interface TimelineRow {
  id: string;
  label: string;
  isWorkflow: boolean;
  attempts: TimelineAttempt[];
}

interface TimelineModel {
  rows: TimelineRow[];
  minMs: number;
  maxMs: number;
  workflowStatus: AttemptStatus;
  hasTaskRows: boolean;
}

interface TooltipState {
  x: number;
  y: number;
  rowLabel: string;
  attempt: TimelineAttempt;
}

interface DragState {
  startX: number;
  startPanMs: number;
}

interface AttemptWithRow {
  rowLabel: string;
  attempt: TimelineAttempt;
}

const LABEL_WIDTH = 220;
const CHART_PADDING_X = 12;
const AXIS_HEIGHT = 36;
const ROW_HEIGHT = 34;
const BAR_HEIGHT = 16;
const MIN_VISIBLE_SPAN_MS = 1_000;
const ZOOM_MIN = 1;
const ZOOM_MAX = 20;

export function GanttChart({ events }: GanttChartProps) {
  const model = useMemo(() => buildTimelineModel(events), [events]);
  const pendingPatternId = useId();
  const runningPatternId = useId();
  const completedPatternId = useId();
  const faultedPatternId = useId();
  const retriedPatternId = useId();
  const chartInstructionsId = useId();
  const chartDetailsId = useId();
  const chartTitleId = useId();
  const chartDescriptionId = useId();

  const [showLegend, setShowLegend] = useState(true);
  const [zoom, setZoom] = useState(1);
  const [panMs, setPanMs] = useState(0);
  const [laneWidth, setLaneWidth] = useState(760);
  const [dragState, setDragState] = useState<DragState | null>(null);
  const [selectedAttemptId, setSelectedAttemptId] = useState<string | null>(null);
  const [tooltip, setTooltip] = useState<TooltipState | null>(null);

  const canvasRef = useRef<HTMLDivElement | null>(null);
  const detailsRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const element = canvasRef.current;
    if (!element) return;

    const update = () => {
      const nextWidth = Math.max(360, element.clientWidth - LABEL_WIDTH - CHART_PADDING_X * 2);
      setLaneWidth(nextWidth);
    };

    update();

    const observer = new ResizeObserver(update);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  const attemptsWithRows = useMemo<AttemptWithRow[]>(
    () =>
      model.rows.flatMap((row) =>
        row.attempts.map((attempt) => ({
          rowLabel: row.label,
          attempt,
        })),
      ),
    [model.rows],
  );

  const selectedAttempt = useMemo(
    () => attemptsWithRows.find((item) => item.attempt.id === selectedAttemptId) ?? null,
    [attemptsWithRows, selectedAttemptId],
  );

  const baseSpan = Math.max(model.maxMs - model.minMs, MIN_VISIBLE_SPAN_MS);
  const centerBase = model.minMs + baseSpan / 2;
  const clampedZoom = clamp(zoom, ZOOM_MIN, ZOOM_MAX);
  const viewportSpan = baseSpan / clampedZoom;
  const clampedPan = clampPan(panMs, baseSpan, clampedZoom);
  const viewStart = centerBase + clampedPan - viewportSpan / 2;
  const viewEnd = viewStart + viewportSpan;

  const laneStartX = LABEL_WIDTH + CHART_PADDING_X;
  const laneEndX = laneStartX + laneWidth;
  const svgWidth = laneEndX + CHART_PADDING_X;
  const svgHeight = AXIS_HEIGHT + Math.max(model.rows.length, 1) * ROW_HEIGHT + 16;
  const ticks = useMemo(() => buildTicks(viewStart, viewportSpan, 6), [viewStart, viewportSpan]);

  const panBy = (deltaMs: number) => {
    setPanMs((current) => clampPan(current + deltaMs, baseSpan, clampedZoom));
  };

  const zoomBy = (scale: number, anchorRatio = 0.5) => {
    const ratio = clamp(anchorRatio, 0, 1);
    const nextZoom = clamp(clampedZoom * scale, ZOOM_MIN, ZOOM_MAX);
    const anchorTime = viewStart + viewportSpan * ratio;
    const nextViewportSpan = baseSpan / nextZoom;
    const nextViewStart = anchorTime - nextViewportSpan * ratio;
    const nextCenter = nextViewStart + nextViewportSpan / 2;

    setZoom(nextZoom);
    setPanMs(clampPan(nextCenter - centerBase, baseSpan, nextZoom));
  };

  const resetViewport = () => {
    setZoom(1);
    setPanMs(0);
  };

  useEffect(() => {
    if (!selectedAttempt) return;
    detailsRef.current?.focus();
  }, [selectedAttempt]);

  useEffect(() => {
    if (!dragState) return;

    const onMove = (event: MouseEvent) => {
      const deltaX = event.clientX - dragState.startX;
      const deltaMs = -(deltaX / Math.max(laneWidth, 1)) * viewportSpan;
      setPanMs(clampPan(dragState.startPanMs + deltaMs, baseSpan, clampedZoom));
    };

    const onUp = () => setDragState(null);

    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
    return () => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };
  }, [baseSpan, clampedZoom, dragState, laneWidth, viewportSpan]);

  if (events.length === 0) {
    return (
      <Card>
        <p className="text-sm text-slate-500">No timeline events available for this instance.</p>
      </Card>
    );
  }

  const showPendingHint = model.workflowStatus === "pending" && !model.hasTaskRows;

  return (
    <Card>
      <div className="space-y-3">
          <p id={chartInstructionsId} className="sr-only">
            Timeline controls: tab to a task bar, press Enter or Space to open details, arrow keys to pan, plus or
            minus to zoom, and 0 to fit the full workflow.
          </p>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex flex-wrap items-center gap-2">
              <Button variant="outline" onClick={() => setShowLegend((current) => !current)}>
                {showLegend ? "Hide legend" : "Show legend"}
              </Button>
              {showLegend && (
                <div className="flex flex-wrap items-center gap-3 text-xs text-slate-600 dark:text-slate-300">
                  <Legend color="#94a3b8" label="Queued" pattern="queued" />
                  <Legend color="#3b82f6" label="Executing" pattern="running" />
                  <Legend color="#10b981" label="Completed" pattern="completed" />
                  <Legend color="#ef4444" label="Faulted" pattern="faulted" />
                  <Legend color="#f59e0b" label="Retried" pattern="retried" />
                </div>
              )}
            </div>

            <div className="flex items-center gap-2 text-xs text-slate-500">
              <span>{Math.round(clampedZoom * 100)}%</span>
              <Button
                variant="outline"
                onClick={() => zoomBy(1.15)}
                className="h-8 w-8 px-0"
                aria-label="Zoom in timeline"
              >
                <Plus className="h-3.5 w-3.5" />
              </Button>
              <Button
                variant="outline"
                onClick={() => zoomBy(1 / 1.15)}
                className="h-8 w-8 px-0"
                aria-label="Zoom out timeline"
              >
                <Minus className="h-3.5 w-3.5" />
              </Button>
              <Button variant="outline" onClick={resetViewport} className="gap-2">
                <Maximize2 className="h-3.5 w-3.5" />
                Fit to window
              </Button>
            </div>
          </div>

        {showPendingHint && (
          <p className="text-sm text-slate-500">This workflow is queued and has not started yet.</p>
        )}

        <div
          ref={canvasRef}
          className="relative overflow-x-auto rounded-md border border-slate-200 bg-slate-50 dark:border-slate-800 dark:bg-slate-950/40"
          tabIndex={0}
          role="region"
          aria-label="Workflow timeline chart"
          aria-describedby={chartInstructionsId}
          onKeyDown={(event) => {
            if (event.key === "0") {
              event.preventDefault();
              resetViewport();
              return;
            }

            if (event.key === "+" || event.key === "=") {
              event.preventDefault();
              zoomBy(1.15);
              return;
            }

            if (event.key === "-" || event.key === "_") {
              event.preventDefault();
              zoomBy(1 / 1.15);
              return;
            }

            if (event.key === "Escape") {
              setSelectedAttemptId(null);
            }
          }}
        >
          <svg
            width={svgWidth}
            height={svgHeight}
            className={dragState ? "cursor-grabbing" : "cursor-default"}
            role="img"
            aria-labelledby={`${chartTitleId} ${chartDescriptionId}`}
            onWheel={(event) => {
              event.preventDefault();

              const rect = event.currentTarget.getBoundingClientRect();
              const relativeX = clamp(event.clientX - rect.left - laneStartX, 0, Math.max(laneWidth, 1));
              const ratio = relativeX / Math.max(laneWidth, 1);
              zoomBy(event.deltaY < 0 ? 1.15 : 1 / 1.15, ratio);
            }}
          >
            <title id={chartTitleId}>Workflow and task execution timeline</title>
            <desc id={chartDescriptionId}>
              Horizontal timeline with one workflow row and task rows. Bar colors and patterns encode status, and each
              bar is keyboard focusable.
            </desc>

            <defs>
              <pattern id={pendingPatternId} width="6" height="6" patternUnits="userSpaceOnUse">
                <rect width="6" height="6" fill="transparent" />
                <circle cx="1.5" cy="1.5" r="0.9" fill="rgba(15, 23, 42, 0.45)" />
                <circle cx="4.5" cy="4.5" r="0.9" fill="rgba(15, 23, 42, 0.45)" />
              </pattern>
              <pattern id={runningPatternId} width="8" height="8" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
                <rect width="8" height="8" fill="transparent" />
                <line x1="0" y1="0" x2="0" y2="8" stroke="rgba(15, 23, 42, 0.42)" strokeWidth="1.2" />
                <line x1="4" y1="0" x2="4" y2="8" stroke="rgba(15, 23, 42, 0.32)" strokeWidth="1" />
              </pattern>
              <pattern id={completedPatternId} width="8" height="8" patternUnits="userSpaceOnUse">
                <rect width="8" height="8" fill="transparent" />
                <line x1="0" y1="2" x2="8" y2="2" stroke="rgba(15, 23, 42, 0.35)" strokeWidth="1.1" />
                <line x1="0" y1="6" x2="8" y2="6" stroke="rgba(15, 23, 42, 0.22)" strokeWidth="1" />
              </pattern>
              <pattern id={faultedPatternId} width="8" height="8" patternUnits="userSpaceOnUse">
                <rect width="8" height="8" fill="transparent" />
                <line x1="0" y1="0" x2="8" y2="8" stroke="rgba(15, 23, 42, 0.45)" strokeWidth="1.2" />
                <line x1="8" y1="0" x2="0" y2="8" stroke="rgba(15, 23, 42, 0.32)" strokeWidth="1.2" />
              </pattern>
              <pattern id={retriedPatternId} width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
                <rect width="6" height="6" fill="transparent" />
                <line x1="0" y1="0" x2="0" y2="6" stroke="#1f2937" strokeWidth="1.4" />
              </pattern>
            </defs>

            <rect
              x={laneStartX}
              y={AXIS_HEIGHT - 16}
              width={laneWidth}
              height={svgHeight - AXIS_HEIGHT + 8}
              fill="transparent"
              className={dragState ? "cursor-grabbing" : "cursor-grab"}
              onMouseDown={(event) => {
                setDragState({
                  startX: event.clientX,
                  startPanMs: clampedPan,
                });
              }}
              onDoubleClick={() => resetViewport()}
            />

            {ticks.map((tick, index) => {
              const x = timeToX(tick, viewStart, viewportSpan, laneStartX, laneWidth);
              return (
                <g key={`${tick}-${index}`}>
                  <line x1={x} y1={AXIS_HEIGHT - 8} x2={x} y2={svgHeight - 8} stroke="#cbd5e1" strokeWidth="1" />
                  <text x={x} y={14} textAnchor="middle" fontSize="11" fill="#64748b">
                    {formatAxisTime(tick, viewportSpan)}
                  </text>
                </g>
              );
            })}

            {model.rows.map((row, rowIndex) => {
              const y = AXIS_HEIGHT + rowIndex * ROW_HEIGHT + (ROW_HEIGHT - BAR_HEIGHT) / 2;

              return (
                <g key={row.id}>
                  <text x={CHART_PADDING_X + 4} y={y + 12} fontSize="12" fill="#475569">
                    {row.label}
                  </text>

                  {row.attempts.map((attempt) => {
                    const isSelected = selectedAttemptId === attempt.id;
                    const isRunning = attempt.status === "running";
                    const segments = buildSegments(attempt);
                    const visibleAttemptStart = Math.max(attempt.startMs, viewStart);
                    const visibleAttemptEnd = Math.min(attempt.endMs, viewEnd);
                    if (visibleAttemptEnd <= visibleAttemptStart) return null;

                    const overlayX = timeToX(
                      visibleAttemptStart,
                      viewStart,
                      viewportSpan,
                      laneStartX,
                      laneWidth,
                    );
                    const overlayEnd = timeToX(visibleAttemptEnd, viewStart, viewportSpan, laneStartX, laneWidth);
                    const overlayWidth = Math.max(2, overlayEnd - overlayX);
                    const duration = attempt.endMs - attempt.startMs;

                    return (
                      <g key={attempt.id}>
                        {segments.map((segment, segmentIndex) => {
                          const clippedStart = Math.max(segment.startMs, viewStart);
                          const clippedEnd = Math.min(segment.endMs, viewEnd);
                          if (clippedEnd <= clippedStart) return null;

                          const x = timeToX(clippedStart, viewStart, viewportSpan, laneStartX, laneWidth);
                          const endX = timeToX(clippedEnd, viewStart, viewportSpan, laneStartX, laneWidth);
                          const width = Math.max(2, endX - x);

                          return (
                            <g key={`${attempt.id}-${segmentIndex}`}>
                              <rect
                                x={x}
                                y={y}
                                width={width}
                                height={BAR_HEIGHT}
                                rx={4}
                                fill={segment.color}
                                className={isRunning && segment.phase === "executing" ? "animate-pulse" : undefined}
                              />
                              <rect
                                x={x}
                                y={y}
                                width={width}
                                height={BAR_HEIGHT}
                                rx={4}
                                fill={`url(#${segmentPatternId(
                                  segment.phase,
                                  attempt.status,
                                  pendingPatternId,
                                  runningPatternId,
                                  completedPatternId,
                                  faultedPatternId,
                                  retriedPatternId,
                                )})`}
                                opacity={segment.phase === "queued" ? 0.3 : 0.24}
                              />
                            </g>
                          );
                        })}

                        {isSelected && (
                          <rect
                            x={overlayX - 1}
                            y={y - 1}
                            width={overlayWidth + 2}
                            height={BAR_HEIGHT + 2}
                            rx={5}
                            fill="none"
                            stroke="#06b6d4"
                            strokeWidth="2"
                          />
                        )}

                        <rect
                          x={overlayX}
                          y={y}
                          width={overlayWidth}
                          height={BAR_HEIGHT}
                          fill="transparent"
                          tabIndex={0}
                          role="button"
                          aria-label={`${attempt.label}: ${statusLabel(attempt.status)}, ${formatDuration(duration)}`}
                          aria-expanded={isSelected}
                          aria-controls={chartDetailsId}
                          aria-keyshortcuts="Enter Space ArrowLeft ArrowRight Escape"
                          onClick={() => setSelectedAttemptId(attempt.id)}
                          onKeyDown={(event) => {
                            if (event.key === "Enter" || event.key === " ") {
                              event.preventDefault();
                              setSelectedAttemptId(attempt.id);
                              return;
                            }

                            if (event.key === "ArrowLeft") {
                              event.preventDefault();
                              panBy(-viewportSpan * 0.1);
                              return;
                            }

                            if (event.key === "ArrowRight") {
                              event.preventDefault();
                              panBy(viewportSpan * 0.1);
                              return;
                            }

                            if (event.key === "Escape") {
                              event.preventDefault();
                              setSelectedAttemptId(null);
                            }
                          }}
                          onMouseEnter={(event) => {
                            const bounds = canvasRef.current?.getBoundingClientRect();
                            if (!bounds) return;
                            setTooltip({
                              x: event.clientX - bounds.left + 12,
                              y: event.clientY - bounds.top + 12,
                              rowLabel: row.label,
                              attempt,
                            });
                          }}
                          onMouseMove={(event) => {
                            const bounds = canvasRef.current?.getBoundingClientRect();
                            if (!bounds) return;
                            setTooltip((current) =>
                              current
                                ? {
                                    ...current,
                                    x: event.clientX - bounds.left + 12,
                                    y: event.clientY - bounds.top + 12,
                                  }
                                : current,
                            );
                          }}
                          onMouseLeave={() => setTooltip(null)}
                        />
                      </g>
                    );
                  })}
                </g>
              );
            })}
          </svg>

          {tooltip && (
            <div
              className="pointer-events-none absolute z-10 max-w-xs rounded-md border border-slate-300 bg-white/95 p-2 text-xs shadow-lg dark:border-slate-700 dark:bg-slate-900/95"
              style={{ left: tooltip.x, top: tooltip.y }}
            >
              <p className="font-semibold">{tooltip.rowLabel}</p>
              <p className="text-slate-500">
                {statusLabel(tooltip.attempt.status)} • {formatDuration(tooltip.attempt.endMs - tooltip.attempt.startMs)}
              </p>
              <p className="text-slate-500">
                {new Date(tooltip.attempt.startMs).toLocaleString()} → {new Date(tooltip.attempt.endMs).toLocaleString()}
              </p>
              <p className="mt-1 line-clamp-3 text-slate-500">{payloadPreview(lastPayload(tooltip.attempt.events))}</p>
            </div>
          )}
        </div>

        {selectedAttempt && (
          <div className="rounded-md border border-slate-200 bg-white p-3 dark:border-slate-800 dark:bg-slate-900/60">
            <div id={chartDetailsId} ref={detailsRef} tabIndex={-1} role="region" aria-label="Selected timeline details">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <p className="text-sm font-semibold">
                  {selectedAttempt.rowLabel}
                  {selectedAttempt.attempt.retryIndex > 1 ? ` (attempt ${selectedAttempt.attempt.retryIndex})` : ""}
                </p>
                <div className="flex items-center gap-2">
                  <p className="text-xs text-slate-500">
                    {statusLabel(selectedAttempt.attempt.status)} •{" "}
                    {formatDuration(selectedAttempt.attempt.endMs - selectedAttempt.attempt.startMs)}
                  </p>
                  <Button
                    variant="ghost"
                    className="h-8 px-2 text-xs"
                    onClick={() => setSelectedAttemptId(null)}
                    aria-label="Close selected timeline details"
                  >
                    <X className="h-3.5 w-3.5" />
                    Close
                  </Button>
                </div>
              </div>

              <p className="mt-1 text-xs text-slate-500">
                {new Date(selectedAttempt.attempt.startMs).toLocaleString()} →{" "}
                {new Date(selectedAttempt.attempt.endMs).toLocaleString()}
              </p>

              <div className="mt-3 max-h-56 space-y-2 overflow-auto">
                {selectedAttempt.attempt.events.map((event) => (
                  <div
                    key={`${selectedAttempt.attempt.id}-${event.sequenceBigInt.toString()}`}
                    className="rounded border border-slate-200 bg-slate-50 p-2 dark:border-slate-700 dark:bg-slate-900"
                  >
                    <p className="text-xs font-medium">{humanizeEventType(event.eventType)}</p>
                    <p className="text-xs text-slate-500">{new Date(event.timeMs).toLocaleString()}</p>
                    <pre className="mt-1 max-h-28 overflow-auto rounded bg-slate-950 p-2 text-[11px] text-slate-100">
                      {prettyPayload(event.payloadJson)}
                    </pre>
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}
      </div>
    </Card>
  );
}

function buildTimelineModel(events: TimelineEventLike[]): TimelineModel {
  const normalized = normalizeEvents(events);
  const nowMs = Date.now();

  if (normalized.length === 0) {
    return {
      rows: [],
      minMs: nowMs - MIN_VISIBLE_SPAN_MS,
      maxMs: nowMs,
      workflowStatus: "pending",
      hasTaskRows: false,
    };
  }

  const workflowRow = buildWorkflowRow(normalized, nowMs);
  const taskRows = buildTaskRows(normalized, nowMs);
  const rows = [workflowRow, ...taskRows];

  const allTimes = rows.flatMap((row) => row.attempts.flatMap((attempt) => [attempt.startMs, attempt.endMs]));
  const minMs = Math.min(...allTimes);
  const maxMs = Math.max(...allTimes, minMs + MIN_VISIBLE_SPAN_MS);

  return {
    rows,
    minMs,
    maxMs,
    workflowStatus: workflowRow.attempts[0]?.status ?? "pending",
    hasTaskRows: taskRows.length > 0,
  };
}

function normalizeEvents(events: TimelineEventLike[]): NormalizedTimelineEvent[] {
  return events
    .map((event) => ({
      ...event,
      timeMs: event.eventTime ? new Date(event.eventTime).getTime() : Number.NaN,
      sequenceBigInt: toBigInt(event.sequence),
    }))
    .filter((event) => Number.isFinite(event.timeMs))
    .sort((left, right) => {
      if (left.timeMs !== right.timeMs) return left.timeMs - right.timeMs;
      if (left.sequenceBigInt === right.sequenceBigInt) return 0;
      return left.sequenceBigInt < right.sequenceBigInt ? -1 : 1;
    });
}

function buildWorkflowRow(events: NormalizedTimelineEvent[], nowMs: number): TimelineRow {
  const workflowEvents = events.filter((event) => event.eventType.includes(".workflow."));
  const createdAt = workflowEvents.find((event) => isWorkflowEvent(event.eventType, "created"))?.timeMs ?? events[0].timeMs;
  const startedAt = workflowEvents.find((event) => isWorkflowEvent(event.eventType, "started"))?.timeMs;
  const completedAt = lastEventTime(workflowEvents, "completed");
  const faultedAt = lastEventTime(workflowEvents, "faulted");

  const status = deriveWorkflowStatus(createdAt, startedAt, completedAt, faultedAt);
  const terminalAt = latestTerminal(completedAt, faultedAt);
  const endMs = terminalAt ?? nowMs;

  const attempt: TimelineAttempt = {
    id: "workflow:1",
    pointer: "workflow",
    label: "Workflow",
    retryIndex: 1,
    status,
    startMs: createdAt,
    startedMs: startedAt,
    endMs,
    events: workflowEvents,
  };

  return {
    id: "workflow-row",
    label: "Workflow",
    isWorkflow: true,
    attempts: [attempt],
  };
}

function buildTaskRows(events: NormalizedTimelineEvent[], nowMs: number): TimelineRow[] {
  const groups = new Map<string, NormalizedTimelineEvent[]>();

  for (const event of events) {
    if (!event.eventType.includes(".task.")) continue;
    const pointer = event.taskPointer?.trim() || `task:${event.taskName?.trim() || "unknown"}`;
    const bucket = groups.get(pointer) ?? [];
    bucket.push(event);
    groups.set(pointer, bucket);
  }

  const rows: TimelineRow[] = [];
  for (const [pointer, groupEvents] of groups.entries()) {
    const attempts = buildTaskAttempts(pointer, groupEvents, nowMs);
    if (attempts.length === 0) continue;

    rows.push({
      id: `row:${pointer}`,
      label: attempts[0].label,
      isWorkflow: false,
      attempts,
    });
  }

  return rows.sort((left, right) => left.label.localeCompare(right.label));
}

function buildTaskAttempts(pointer: string, events: NormalizedTimelineEvent[], nowMs: number): TimelineAttempt[] {
  const ordered = [...events].sort((left, right) => {
    if (left.timeMs !== right.timeMs) return left.timeMs - right.timeMs;
    if (left.sequenceBigInt === right.sequenceBigInt) return 0;
    return left.sequenceBigInt < right.sequenceBigInt ? -1 : 1;
  });

  const attempts: TimelineAttempt[] = [];
  let current: MutableAttempt | null = null;

  for (const event of ordered) {
    const kind = taskEventKind(event.eventType);

    if (kind === "created") {
      if (current && (current.status === "pending" || current.status === "running")) {
        current.status = "retried";
        current.endMs = Math.max(current.endMs, event.timeMs);
        attempts.push(finalizeAttempt(current, pointer, attempts.length + 1));
      }

      current = {
        label: resolveTaskLabel(pointer, ordered),
        status: "pending",
        startMs: event.timeMs,
        startedMs: undefined,
        endMs: event.timeMs,
        events: [event],
      };
      continue;
    }

    if (!current) {
      current = {
        label: resolveTaskLabel(pointer, ordered),
        status: kind === "started" ? "running" : kind === "retried" ? "retried" : "pending",
        startMs: event.timeMs,
        startedMs: kind === "started" ? event.timeMs : undefined,
        endMs: event.timeMs,
        events: [event],
      };
    } else {
      current.events.push(event);
      current.endMs = Math.max(current.endMs, event.timeMs);
    }

    if (kind === "started") {
      current.startedMs = current.startedMs ?? event.timeMs;
      if (current.status === "pending") current.status = "running";
      continue;
    }

    if (kind === "completed" || kind === "faulted") {
      current.status = kind;
      attempts.push(finalizeAttempt(current, pointer, attempts.length + 1));
      current = null;
      continue;
    }

    if (kind === "retried") {
      current.status = "retried";
      attempts.push(finalizeAttempt(current, pointer, attempts.length + 1));
      current = {
        label: resolveTaskLabel(pointer, ordered),
        status: "pending",
        startMs: event.timeMs,
        startedMs: undefined,
        endMs: event.timeMs,
        events: [],
      };
    }
  }

  if (current) {
    if (current.status === "pending" || current.status === "running") {
      current.endMs = Math.max(current.endMs, nowMs);
    }
    attempts.push(finalizeAttempt(current, pointer, attempts.length + 1));
  }

  return attempts;
}

function finalizeAttempt(attempt: MutableAttempt, pointer: string, retryIndex: number): TimelineAttempt {
  return {
    id: `${pointer}:${retryIndex}:${attempt.startMs}`,
    pointer,
    label: attempt.label,
    retryIndex,
    status: attempt.status,
    startMs: attempt.startMs,
    startedMs: attempt.startedMs,
    endMs: Math.max(attempt.endMs, attempt.startMs + 1),
    events: [...attempt.events],
  };
}

function buildSegments(attempt: TimelineAttempt): TimelineSegment[] {
  const started = attempt.startedMs;
  const statusColor = colorForStatus(attempt.status);

  if (started == null || started <= attempt.startMs) {
    return [
      {
        phase: "queued",
        startMs: attempt.startMs,
        endMs: attempt.endMs,
        color: attempt.status === "pending" ? statusColor : colorForStatus(attempt.status),
      },
    ];
  }

  return [
    {
      phase: "queued",
      startMs: attempt.startMs,
      endMs: started,
      color: colorForStatus("pending"),
    },
    {
      phase: "executing",
      startMs: started,
      endMs: attempt.endMs,
      color: statusColor,
    },
  ];
}

function buildTicks(viewStart: number, viewportSpan: number, count: number): number[] {
  if (count < 2) return [viewStart];
  return Array.from({ length: count }, (_, index) => viewStart + (viewportSpan * index) / (count - 1));
}

function timeToX(
  timeMs: number,
  viewStart: number,
  viewportSpan: number,
  laneStartX: number,
  laneWidth: number,
): number {
  const ratio = (timeMs - viewStart) / Math.max(viewportSpan, 1);
  return laneStartX + ratio * laneWidth;
}

function formatAxisTime(valueMs: number, viewportSpanMs: number): string {
  const date = new Date(valueMs);
  if (viewportSpanMs >= 24 * 60 * 60 * 1000) {
    return date.toLocaleString([], { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
  }

  if (viewportSpanMs >= 60 * 60 * 1000) {
    return date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  }

  return date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

function colorForStatus(status: AttemptStatus): string {
  switch (status) {
    case "pending":
      return "#94a3b8";
    case "running":
      return "#3b82f6";
    case "completed":
      return "#10b981";
    case "faulted":
      return "#ef4444";
    case "retried":
      return "#f59e0b";
    default:
      return "#94a3b8";
  }
}

function statusLabel(status: AttemptStatus): string {
  switch (status) {
    case "pending":
      return "Queued";
    case "running":
      return "Running";
    case "completed":
      return "Completed";
    case "faulted":
      return "Faulted";
    case "retried":
      return "Retried";
    default:
      return "Unknown";
  }
}

function deriveWorkflowStatus(
  createdAt: number,
  startedAt: number | undefined,
  completedAt: number | undefined,
  faultedAt: number | undefined,
): AttemptStatus {
  if (completedAt != null && faultedAt != null) {
    return completedAt >= faultedAt ? "completed" : "faulted";
  }
  if (completedAt != null) return "completed";
  if (faultedAt != null) return "faulted";
  if (startedAt != null) return "running";
  if (Number.isFinite(createdAt)) return "pending";
  return "running";
}

function latestTerminal(completedAt?: number, faultedAt?: number): number | undefined {
  if (completedAt == null) return faultedAt;
  if (faultedAt == null) return completedAt;
  return Math.max(completedAt, faultedAt);
}

function lastEventTime(events: NormalizedTimelineEvent[], kind: "completed" | "faulted"): number | undefined {
  for (let index = events.length - 1; index >= 0; index -= 1) {
    if (isWorkflowEvent(events[index].eventType, kind)) {
      return events[index].timeMs;
    }
  }
  return undefined;
}

function isWorkflowEvent(eventType: string, kind: "created" | "started" | "completed" | "faulted"): boolean {
  return eventType.includes(`workflow.${kind}`);
}

function taskEventKind(eventType: string): "created" | "started" | "completed" | "faulted" | "retried" | "other" {
  if (eventType.includes("task.created")) return "created";
  if (eventType.includes("task.started")) return "started";
  if (eventType.includes("task.completed")) return "completed";
  if (eventType.includes("task.faulted")) return "faulted";
  if (eventType.includes("task.retried")) return "retried";
  return "other";
}

function resolveTaskLabel(pointer: string, events: NormalizedTimelineEvent[]): string {
  const taskName = events.find((event) => event.taskName?.trim())?.taskName?.trim();
  if (taskName) return taskName;

  if (!pointer.startsWith("/")) return pointer;
  const segments = pointer
    .split("/")
    .filter(Boolean)
    .map((token) => token.replace("~1", "/").replace("~0", "~"));

  if (segments.length === 0) return pointer;
  return segments[segments.length - 1];
}

function humanizeEventType(eventType: string): string {
  const simplified = eventType
    .replace("io.serverlessworkflow.", "")
    .replace(".v1", "")
    .replaceAll(".", " ");
  return simplified;
}

function prettyPayload(payloadJson: string): string {
  try {
    return JSON.stringify(JSON.parse(payloadJson), null, 2);
  } catch {
    return payloadJson;
  }
}

function payloadPreview(payloadJson: string): string {
  if (!payloadJson) return "-";

  try {
    const payload = JSON.parse(payloadJson);
    const candidate = payload?.data?.output ?? payload?.data?.error ?? payload?.data ?? payload;
    return clipString(JSON.stringify(candidate), 180);
  } catch {
    return clipString(payloadJson, 180);
  }
}

function lastPayload(events: NormalizedTimelineEvent[]): string {
  if (events.length === 0) return "";
  return events[events.length - 1].payloadJson;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function clampPan(pan: number, baseSpan: number, zoom: number): number {
  const viewportSpan = baseSpan / zoom;
  const maxPan = (baseSpan - viewportSpan) / 2;
  return clamp(pan, -maxPan, maxPan);
}

function clipString(value: string, maxLength: number): string {
  if (value.length <= maxLength) return value;
  return `${value.slice(0, maxLength - 3)}...`;
}

function toBigInt(value: bigint | number | string): bigint {
  if (typeof value === "bigint") return value;
  if (typeof value === "number") return BigInt(value);
  const parsed = Number(value);
  if (Number.isNaN(parsed)) return 0n;
  return BigInt(parsed);
}

type LegendPattern = "queued" | "running" | "completed" | "faulted" | "retried";

function Legend({ color, label, pattern }: { color: string; label: string; pattern: LegendPattern }) {
  return (
    <span className="inline-flex items-center gap-1.5">
      <span
        className="relative h-2.5 w-2.5 rounded-sm"
        style={{ backgroundColor: color }}
      >
        <span className="absolute inset-0" style={{ backgroundImage: legendPatternStyle(pattern) }} />
      </span>
      {label}
    </span>
  );
}

function legendPatternStyle(pattern: LegendPattern): string {
  switch (pattern) {
    case "queued":
      return "repeating-radial-gradient(circle at 1px 1px, rgba(15,23,42,0.55) 0, rgba(15,23,42,0.55) 1px, transparent 1px, transparent 4px)";
    case "running":
      return "repeating-linear-gradient(45deg, transparent, transparent 2px, rgba(15,23,42,0.5) 2px, rgba(15,23,42,0.5) 3px)";
    case "completed":
      return "repeating-linear-gradient(0deg, rgba(15,23,42,0.45), rgba(15,23,42,0.45) 1px, transparent 1px, transparent 3px)";
    case "faulted":
      return "repeating-linear-gradient(45deg, rgba(15,23,42,0.5), rgba(15,23,42,0.5) 1px, transparent 1px, transparent 3px), repeating-linear-gradient(-45deg, rgba(15,23,42,0.35), rgba(15,23,42,0.35) 1px, transparent 1px, transparent 3px)";
    case "retried":
      return "repeating-linear-gradient(45deg, transparent, transparent 1px, rgba(15,23,42,0.58) 1px, rgba(15,23,42,0.58) 2px)";
    default:
      return "";
  }
}

function segmentPatternId(
  phase: TimelineSegment["phase"],
  status: AttemptStatus,
  pendingPatternId: string,
  runningPatternId: string,
  completedPatternId: string,
  faultedPatternId: string,
  retriedPatternId: string,
): string {
  if (phase === "queued") return pendingPatternId;

  switch (status) {
    case "running":
      return runningPatternId;
    case "completed":
      return completedPatternId;
    case "faulted":
      return faultedPatternId;
    case "retried":
      return retriedPatternId;
    case "pending":
      return pendingPatternId;
    default:
      return pendingPatternId;
  }
}

interface TimelineSegment {
  phase: "queued" | "executing";
  startMs: number;
  endMs: number;
  color: string;
}

interface MutableAttempt {
  label: string;
  status: AttemptStatus;
  startMs: number;
  startedMs?: number;
  endMs: number;
  events: NormalizedTimelineEvent[];
}
