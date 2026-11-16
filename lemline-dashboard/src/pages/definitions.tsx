import { useMemo, useState, type KeyboardEvent, type MouseEvent } from "react";
import { useGlobalNamespace } from "../context/namespace-context";
import { useCatalog, workflowKey } from "../hooks/use-catalog";
import { buildPageTitle, useDocumentMeta } from "../hooks/use-document-meta";
import { useDashboardNavigation } from "../hooks/use-navigation";
import { useDefinitionStatsRows } from "../hooks/use-status-counts";
import { useViewportWidth } from "../hooks/use-viewport-width";
import { asNumber } from "../lib/utils";
import { ErrorBlock, LoadingBlock } from "../components/lm/empty-state";
import { PageHeader, SectionTitle } from "../components/lm/page-header";
import { usePagePadding } from "../hooks/use-layout";
import type { UiStatus } from "../lib/status";

interface Activity {
  running: number;
  faulted: number;
}

const COLUMNS = "minmax(220px,1fr) 220px 200px";

function enterKey(action: () => void) {
  return (event: KeyboardEvent) => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      action();
    }
  };
}

export function DefinitionsPage() {
  useDocumentMeta(buildPageTitle(["Definitions"]));
  const pagePad = usePagePadding();
  const compact = useViewportWidth() < 900;
  const { namespace } = useGlobalNamespace();
  const catalog = useCatalog();
  const { openDefinition, seeInstances } = useDashboardNavigation();
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});

  // "Active" = running now, or faulted whatever their age.
  const stats = useDefinitionStatsRows({ namespace: namespace || undefined });
  const activity = useMemo(() => {
    const map = new Map<string, Activity>();
    for (const stat of stats.data?.stats ?? []) {
      const key = `${stat.namespace}/${stat.name}@${stat.version}`;
      const current = map.get(key) ?? { running: 0, faulted: 0 };
      current.running += asNumber(stat.runningCountAtTimeTo);
      current.faulted += asNumber(stat.faultedCount);
      map.set(key, current);
    }
    return map;
  }, [stats.data]);

  const activityOf = (ns: string, wf: string, versions: string[]): Activity =>
    versions.reduce(
      (acc, version) => {
        const item = activity.get(`${ns}/${wf}@${version}`);
        return { running: acc.running + (item?.running ?? 0), faulted: acc.faulted + (item?.faulted ?? 0) };
      },
      { running: 0, faulted: 0 },
    );

  const visible = catalog.workflows.filter((entry) => !namespace || entry.ns === namespace);
  const groups = Array.from(new Set(visible.map((entry) => entry.ns))).map((ns) => ({
    ns,
    items: visible.filter((entry) => entry.ns === ns),
  }));

  const activeButton = (ns: string, wf: string, version: string, value: Activity, long: boolean) => {
    const total = value.running + value.faulted;
    const status: UiStatus[] =
      value.running > 0 && value.faulted > 0 ? ["running", "faulted"] : value.running > 0 ? ["running"] : ["faulted"];
    return (
      <button
        type="button"
        disabled={total === 0}
        title={total === 0 ? "No instance running or faulted" : "See these instances"}
        onClick={(event: MouseEvent) => {
          event.stopPropagation();
          if (total > 0) seeInstances({ ns, wf, ver: version, statuses: status, period: "all" });
        }}
        className={
          total === 0
            ? "min-h-8 border-0 bg-transparent p-0 text-left text-sm tabular-nums text-lm-muted"
            : "min-h-8 cursor-pointer border-0 bg-transparent p-0 text-left text-sm tabular-nums text-lm-accent underline decoration-lm-line-strong underline-offset-[3px]"
        }
      >
        {total === 0 ? (long ? "No active instance" : "None") : ""}
        {value.running > 0 && <span>{value.running} running</span>}
        {value.running > 0 && value.faulted > 0 ? ", " : ""}
        {value.faulted > 0 && <span className="font-semibold text-lm-fail">{value.faulted} faulted</span>}
      </button>
    );
  };

  const versionsChip = (key: string, count: number) => {
    const open = Boolean(expanded[key]);
    return (
      <button
        type="button"
        aria-expanded={open}
        title="Show each version"
        onClick={(event: MouseEvent) => {
          event.stopPropagation();
          setExpanded((previous) => ({ ...previous, [key]: !previous[key] }));
        }}
        className="inline-flex min-h-8 cursor-pointer items-center gap-1.5 rounded-full border border-lm-line-strong bg-lm-subtle px-2.5 text-[13px] font-semibold text-lm-text2"
      >
        All <span className="font-normal text-lm-muted">· {count} versions</span>
        <svg
          width="12"
          height="12"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2.4"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
          style={{ transform: `rotate(${open ? 180 : 0}deg)`, transition: "transform 0.15s" }}
        >
          <path d="m6 9 6 6 6-6" />
        </svg>
      </button>
    );
  };

  return (
    <div className="flex min-h-[760px] w-full flex-col bg-lm-bg">
      <PageHeader title="Definitions" />
      <div className="flex flex-col gap-4" style={{ padding: pagePad }}>
        {catalog.isLoading && <LoadingBlock label="Loading definitions" height={200} />}
        {catalog.isError && !catalog.data && (
          <ErrorBlock text="The definitions could not be loaded." onRetry={() => void catalog.refetch()} />
        )}

        {visible.length > 0 && (
          <div className="flex flex-col gap-8">
            {groups.map((group) => (
              <section key={group.ns} aria-label={`Namespace ${group.ns}`} className="flex flex-col gap-3">
                <SectionTitle>{group.ns}</SectionTitle>
                <div className="overflow-hidden overflow-x-auto rounded-[14px] border border-lm-line bg-lm-panel shadow-lm-card">
                  <div className={compact ? undefined : "min-w-[660px]"}>
                    {!compact && (
                      <div
                        className="grid gap-4 border-b-2 border-lm-line-strong bg-lm-panel px-5 py-2.5 text-[13px] font-semibold text-lm-muted"
                        style={{ gridTemplateColumns: COLUMNS }}
                      >
                        <span>Name</span>
                        <span>Version</span>
                        <span title="Running or faulted, whatever their age">Active instances</span>
                      </div>
                    )}
                    {group.items.map((entry) => {
                      const key = workflowKey(entry.ns, entry.wf);
                      const multi = entry.versions.length > 1;
                      const open = multi && Boolean(expanded[key]);
                      const rowAction = multi
                        ? () => setExpanded((previous) => ({ ...previous, [key]: !previous[key] }))
                        : () => openDefinition(entry.ns, entry.wf);
                      const total = activityOf(entry.ns, entry.wf, entry.versions);
                      const versionCell = multi ? versionsChip(key, entry.versions.length) : <span>v{entry.versions[0]}</span>;
                      return (
                        <div key={key}>
                          <div
                            className={
                              compact
                                ? "lm-row-hover flex cursor-pointer flex-col items-start gap-1.5 border-b border-lm-line-soft px-4 pb-3.5 pt-3 text-sm"
                                : "lm-row-hover grid cursor-pointer items-center gap-4 border-b border-lm-line-soft px-5 py-3 text-sm"
                            }
                            style={compact ? undefined : { gridTemplateColumns: COLUMNS }}
                            role="link"
                            tabIndex={0}
                            aria-label={multi ? `Show the versions of ${entry.wf}` : `Open ${entry.wf}`}
                            aria-expanded={multi ? open : undefined}
                            onClick={rowAction}
                            onKeyDown={enterKey(rowAction)}
                          >
                            <span className="text-[15px] font-semibold text-lm-accent">{entry.wf}</span>
                            {compact ? (
                              <span className="flex flex-wrap items-center gap-2.5 text-lm-muted">
                                <span>{versionCell}</span>
                                {activeButton(entry.ns, entry.wf, "", total, true)}
                              </span>
                            ) : (
                              <>
                                <span>{versionCell}</span>
                                <span>{activeButton(entry.ns, entry.wf, "", total, false)}</span>
                              </>
                            )}
                          </div>
                          {open &&
                            entry.versions.map((version) => {
                              const openVersion = () => openDefinition(entry.ns, entry.wf, version);
                              const value = activityOf(entry.ns, entry.wf, [version]);
                              return compact ? (
                                <div
                                  key={version}
                                  role="link"
                                  tabIndex={0}
                                  aria-label={`Open ${entry.wf} v${version}`}
                                  onClick={openVersion}
                                  onKeyDown={enterKey(openVersion)}
                                  className="lm-row-hover flex cursor-pointer flex-wrap items-center justify-between gap-2 border-b border-lm-line-soft bg-lm-subtle py-2 pl-8 pr-4 text-sm"
                                >
                                  <span>v{version}</span>
                                  {activeButton(entry.ns, entry.wf, version, value, true)}
                                </div>
                              ) : (
                                <div
                                  key={version}
                                  role="link"
                                  tabIndex={0}
                                  aria-label={`Open ${entry.wf} v${version}`}
                                  onClick={openVersion}
                                  onKeyDown={enterKey(openVersion)}
                                  className="lm-row-hover grid cursor-pointer items-center gap-4 border-b border-lm-line-soft bg-lm-subtle px-5 py-2 text-sm"
                                  style={{ gridTemplateColumns: COLUMNS }}
                                >
                                  <span aria-hidden="true" className="pl-4 text-lm-line-strong">
                                    └
                                  </span>
                                  <span>v{version}</span>
                                  <span>{activeButton(entry.ns, entry.wf, version, value, false)}</span>
                                </div>
                              );
                            })}
                        </div>
                      );
                    })}
                  </div>
                </div>
              </section>
            ))}
          </div>
        )}

        {!catalog.isLoading && visible.length === 0 && !catalog.isError && (
          <div className="flex flex-col items-start gap-2 rounded-[14px] border border-dashed border-lm-line-strong bg-lm-panel px-6 py-7">
            <p className="m-0 text-base font-semibold">
              {namespace ? `No workflow in namespace ${namespace}` : "No workflow deployed yet"}
            </p>
            <p className="m-0 text-sm leading-normal text-lm-muted">
              {namespace ? "Pick another namespace at the top of the page, or All." : "Deployed workflow definitions appear here."}
            </p>
          </div>
        )}
      </div>
    </div>
  );
}
