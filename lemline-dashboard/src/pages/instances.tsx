import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { useGlobalNamespace } from "../context/namespace-context";
import { useCatalog } from "../hooks/use-catalog";
import { useDefinition } from "../hooks/use-definition";
import { buildPageTitle, useDocumentMeta } from "../hooks/use-document-meta";
import { useInstanceList } from "../hooks/use-instance-list";
import { useNow } from "../hooks/use-now";
import { useDashboardNavigation } from "../hooks/use-navigation";
import { useStatusCounts } from "../hooks/use-status-counts";
import { toInstanceRow } from "../lib/instance-row";
import { instancesPath } from "../lib/paths";
import { isPeriodKey, periodFrom, periodOf, PERIODS, type PeriodKey } from "../lib/period";
import { cn } from "../lib/utils";
import { parseWorkflowSource } from "../lib/workflow-source";
import { Dropdown } from "../components/lm/dropdown";
import { EmptyState, ErrorBlock, LoadingBlock } from "../components/lm/empty-state";
import { InstanceSearch } from "../components/lm/instance-search";
import { InstancesTable } from "../components/lm/instances-table";
import { PageHeader } from "../components/lm/page-header";
import { usePagePadding } from "../hooks/use-layout";
import { UI_STATUSES, type UiStatus } from "../lib/status";

const PER_PAGE_OPTIONS = [10, 25, 50, 100];

const STATUS_TONE: Record<UiStatus, { on: string; box: string }> = {
  running: { on: "border-lm-run-line bg-lm-run-soft text-lm-run-strong", box: "border-lm-run bg-lm-run" },
  faulted: { on: "border-lm-fail-line bg-lm-fail-soft text-lm-fail", box: "border-lm-fail bg-lm-fail" },
  completed: { on: "border-lm-done-line bg-lm-done-soft text-lm-done", box: "border-lm-done bg-lm-done" },
};

function readStatuses(value: string | null): UiStatus[] {
  if (value === null) return UI_STATUSES;
  if (value === "none") return [];
  const picked = value.split(",").filter((item): item is UiStatus => (UI_STATUSES as string[]).includes(item));
  return picked.length > 0 ? UI_STATUSES.filter((status) => picked.includes(status)) : UI_STATUSES;
}

function useInstancesUrlState() {
  const [params, setParams] = useSearchParams();
  const wfParam = params.get("wf") ?? "";
  const slash = wfParam.indexOf("/");
  const scope = {
    ns: slash > 0 ? wfParam.slice(0, slash) : "",
    wf: slash > 0 ? wfParam.slice(slash + 1) : "",
    ver: params.get("ver") ?? "",
  };
  const statuses = readStatuses(params.get("status"));
  const periodParam = params.get("period");
  const period: PeriodKey = isPeriodKey(periodParam) ? periodParam : "24h";

  const update = (next: { ns?: string; wf?: string; ver?: string; statuses?: UiStatus[]; period?: PeriodKey }) => {
    const merged = {
      ns: next.ns ?? scope.ns,
      wf: next.wf ?? scope.wf,
      ver: next.ver ?? scope.ver,
      statuses: next.statuses ?? statuses,
      period: next.period ?? period,
    };
    const path = instancesPath(merged);
    const search = path.includes("?") ? path.slice(path.indexOf("?")) : "";
    const nextParams = new URLSearchParams(search);
    if (merged.statuses.length === 0) nextParams.set("status", "none");
    setParams(nextParams, { replace: true });
  };

  return { scope, statuses, period, update };
}

export function InstancesPage() {
  useDocumentMeta(buildPageTitle(["Instances"]));
  const pagePad = usePagePadding();
  const { namespace } = useGlobalNamespace();
  const { workflows } = useCatalog();
  const { openInstance, openDefinition } = useDashboardNavigation();
  const { scope, statuses, period, update } = useInstancesUrlState();
  const nowMs = useNow(30_000);
  const [perPage, setPerPage] = useState(10);
  const [page, setPage] = useState(0);

  // Changing the global namespace drops a workflow filter from another namespace.
  useEffect(() => {
    if (namespace && scope.ns && scope.ns !== namespace) update({ ns: "", wf: "", ver: "" });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [namespace]);

  const filterKey = JSON.stringify([namespace, scope, statuses, period, perPage]);
  const [pageKey, setPageKey] = useState(filterKey);
  if (pageKey !== filterKey) {
    setPageKey(filterKey);
    setPage(0);
  }

  const timeFrom = useMemo(() => periodFrom(period), [period]);
  const queryNamespace = scope.ns || namespace || undefined;
  const list = useInstanceList({
    namespace: queryNamespace,
    name: scope.wf || undefined,
    version: scope.ver || undefined,
    statuses,
    timeFrom,
    perPage,
    page,
  });
  const { counts } = useStatusCounts({
    namespace: queryNamespace,
    name: scope.wf || undefined,
    version: scope.ver || undefined,
    timeFrom,
  });

  const entry = workflows.find((item) => item.ns === scope.ns && item.wf === scope.wf);
  const definitionQuery = useDefinition(scope.wf ? scope.ns : undefined, scope.wf || undefined);
  const trigger = useMemo(() => parseWorkflowSource(definitionQuery.data?.source).trigger, [definitionQuery.data?.source]);

  const selectedCount = counts ? statuses.reduce((sum, status) => sum + counts[status], 0) : undefined;
  const total = list.exactTotal ?? selectedCount ?? list.rows.length;
  const pages = Math.max(1, Math.ceil(total / perPage));
  const current = Math.min(page, pages - 1);
  const rows = list.pageRows.map((instance) => toInstanceRow(instance, nowMs));
  const showLoading = list.loadingPage && rows.length === 0;
  const isEmpty = !showLoading && !list.isError && rows.length === 0;

  const workflowOptions = [{ value: "", label: "All workflows" }].concat(
    workflows
      .filter((item) => !namespace || item.ns === namespace)
      .map((item) => ({ value: `${item.ns}/${item.wf}`, label: namespace ? item.wf : `${item.ns} / ${item.wf}` })),
  );
  const versionOptions = [{ value: "", label: "All versions" }].concat(
    (entry?.versions ?? (scope.ver ? [scope.ver] : [])).map((version) => ({ value: version, label: `v${version}` })),
  );

  const toggleStatus = (status: UiStatus) => {
    const on = statuses.includes(status);
    update({ statuses: on ? statuses.filter((item) => item !== status) : UI_STATUSES.filter((item) => item === status || statuses.includes(item)) });
  };

  const statusWord = statuses.length === UI_STATUSES.length || statuses.length === 0 ? "" : ` ${statuses.join(" or ")}`;
  const phrase = periodOf(period).phrase;
  const emptyTitle = scope.wf
    ? `No${statusWord} instance of ${scope.wf}${phrase ? ` ${phrase}` : ""}`
    : `No${statusWord} instance matches these filters`;
  let emptyText = statuses.length === 0 ? "Tick at least one status." : "Try another status or a longer period.";
  if (scope.wf && trigger.kind === "event" && statuses.length > 0) {
    emptyText = `${scope.wf} starts when the event ${trigger.eventTypes.join(" or ")} arrives. None has arrived in this period, so nothing has started.`;
  }

  const first = current * perPage + 1;
  const last = current * perPage + rows.length;
  const footer = `${total > perPage ? `Showing ${first}–${last} of ${total}` : `Showing ${rows.length}`} ${total === 1 ? "instance" : "instances"}${namespace ? ` (namespace ${namespace} only)` : ""}.`;

  return (
    <div className="flex min-h-[760px] w-full flex-col bg-lm-bg">
      <PageHeader title="Instances" />
      <div className="flex flex-col gap-5" style={{ padding: pagePad }}>
        <div className="flex flex-wrap items-center gap-3">
          <InstanceSearch namespace={namespace} onOpen={openInstance} />
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <Dropdown
            label="Workflow"
            listLabel="Workflow"
            value={scope.wf ? `${scope.ns}/${scope.wf}` : ""}
            valueLabel={scope.wf ? (namespace ? scope.wf : `${scope.ns} / ${scope.wf}`) : "All workflows"}
            options={workflowOptions}
            active={Boolean(scope.wf)}
            onChange={(value) => {
              const slash = value.indexOf("/");
              update(slash > 0 ? { ns: value.slice(0, slash), wf: value.slice(slash + 1), ver: "" } : { ns: "", wf: "", ver: "" });
            }}
          />
          <Dropdown
            label="Version"
            listLabel="Version"
            value={scope.ver}
            valueLabel={scope.ver ? `v${scope.ver}` : "All versions"}
            options={versionOptions}
            disabled={!scope.wf}
            active={Boolean(scope.ver)}
            onChange={(value) => update({ ver: value })}
          />
          <Dropdown
            label="Period"
            listLabel="Period"
            value={period}
            valueLabel={periodOf(period).label}
            options={PERIODS.map((item) => ({ value: item.key, label: item.label }))}
            onChange={(value) => update({ period: value })}
          />
          <div role="group" aria-label="Status" className="flex flex-wrap items-center gap-2">
            {UI_STATUSES.map((status) => {
              const on = statuses.includes(status);
              return (
                <button
                  key={status}
                  type="button"
                  role="checkbox"
                  aria-checked={on}
                  onClick={() => toggleStatus(status)}
                  className={cn(
                    "flex min-h-10 cursor-pointer items-center gap-2 rounded-lg border px-3 text-sm font-semibold",
                    on ? STATUS_TONE[status].on : "border-lm-line-strong bg-lm-panel text-lm-muted",
                  )}
                >
                  <span
                    aria-hidden="true"
                    className={cn(
                      "inline-flex h-4 w-4 items-center justify-center rounded border-[1.5px] text-xs leading-none text-lm-panel",
                      on ? STATUS_TONE[status].box : "border-lm-line-strong bg-transparent",
                    )}
                  >
                    {on ? "✓" : ""}
                  </span>
                  {status[0].toUpperCase() + status.slice(1)}
                  <span className="font-normal text-lm-muted">{counts ? counts[status] : "…"}</span>
                </button>
              );
            })}
          </div>
        </div>

        {list.isError && rows.length === 0 && (
          <ErrorBlock text="The instances could not be loaded." onRetry={() => void list.refetch()} />
        )}
        {showLoading && <LoadingBlock label="Loading instances" height={320} />}

        {rows.length > 0 && (
          <>
            <InstancesTable rows={rows} onOpen={openInstance} onOpenDefinition={(ns, wf) => openDefinition(ns, wf)} />
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="flex flex-wrap items-center gap-3">
                <p className="m-0 text-[13px] text-lm-muted">{footer}</p>
                {total > 10 && (
                  <Dropdown
                    small
                    up
                    label="Per page"
                    listLabel="Instances per page"
                    value={String(perPage)}
                    valueLabel={String(perPage)}
                    options={PER_PAGE_OPTIONS.map((value) => ({ value: String(value), label: String(value) }))}
                    onChange={(value) => setPerPage(Number(value))}
                    className="[&_[role=listbox]]:min-w-full"
                  />
                )}
              </div>
              {pages > 1 && <Pagination page={current} pages={pages} onPage={setPage} />}
            </div>
          </>
        )}

        {isEmpty && (
          <EmptyState
            title={emptyTitle}
            text={emptyText}
            primaryLabel={scope.wf ? "Open definition" : undefined}
            onPrimary={() => openDefinition(scope.ns, scope.wf, scope.ver || undefined)}
            secondaryLabel="Clear filters"
            onSecondary={() => update({ ns: "", wf: "", ver: "", statuses: UI_STATUSES })}
          />
        )}
      </div>
    </div>
  );
}

function Pagination({ page, pages, onPage }: { page: number; pages: number; onPage: (page: number) => void }) {
  let sequence: number[];
  if (pages <= 7) sequence = Array.from({ length: pages }, (_, index) => index);
  else if (page < 4) sequence = [0, 1, 2, 3, 4, -1, pages - 1];
  else if (page > pages - 5) sequence = [0, -1, pages - 5, pages - 4, pages - 3, pages - 2, pages - 1];
  else sequence = [0, -1, page - 1, page, page + 1, -2, pages - 1];

  const arrow = "min-h-9 cursor-pointer rounded-lg border border-lm-line-strong bg-lm-panel px-3 text-sm font-semibold text-lm-text disabled:opacity-45";
  return (
    <nav aria-label="Pages" className="flex flex-wrap items-center gap-1">
      <button type="button" aria-label="Previous page" disabled={page === 0} onClick={() => onPage(page - 1)} className={arrow}>
        ‹ Previous
      </button>
      {sequence.map((index, position) =>
        index < 0 ? (
          <span key={`gap-${position}`} aria-hidden="true" className="min-w-6 text-center text-lm-muted">
            …
          </span>
        ) : (
          <button
            key={index}
            type="button"
            aria-label={`Page ${index + 1}`}
            aria-current={index === page ? "page" : undefined}
            onClick={() => onPage(index)}
            className={cn(
              "min-h-9 min-w-9 cursor-pointer rounded-lg border px-2 text-sm",
              index === page
                ? "border-lm-accent bg-lm-accent-soft font-bold text-lm-accent"
                : "border-lm-line-strong bg-lm-panel font-normal text-lm-text",
            )}
          >
            {index + 1}
          </button>
        ),
      )}
      <button type="button" aria-label="Next page" disabled={page === pages - 1} onClick={() => onPage(page + 1)} className={arrow}>
        Next ›
      </button>
    </nav>
  );
}
