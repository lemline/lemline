import { useMemo, useState } from "react";
import { useParams, useSearchParams } from "react-router-dom";
import { useCatalog } from "../hooks/use-catalog";
import { useDefinition } from "../hooks/use-definition";
import { buildPageTitle, useDocumentMeta } from "../hooks/use-document-meta";
import { useDashboardNavigation } from "../hooks/use-navigation";
import { useStatusCounts } from "../hooks/use-status-counts";
import { compareVersionsDesc } from "../lib/format";
import { periodFrom } from "../lib/period";
import { cn } from "../lib/utils";
import { parseWorkflowSource } from "../lib/workflow-source";
import { CodeBlock } from "../components/lm/code-block";
import { Dropdown } from "../components/lm/dropdown";
import { ErrorBlock, LoadingBlock } from "../components/lm/empty-state";
import { PlayIcon } from "../components/lm/icons";
import { PageHeader } from "../components/lm/page-header";
import { usePagePadding } from "../hooks/use-layout";
import { StartDrawer } from "../components/lm/start-drawer";
import { STATUS_LABEL, type UiStatus } from "../lib/status";
import { TriggerCard } from "../components/lm/trigger-card";

const STAT_STATUSES: UiStatus[] = ["running", "faulted", "completed"];

export function DefinitionPage() {
  const { namespace = "", name = "" } = useParams();
  const [params, setParams] = useSearchParams();
  const pagePad = usePagePadding();
  const { navigate, seeInstances, openInstance } = useDashboardNavigation();
  const catalog = useCatalog();
  const [startOpen, setStartOpen] = useState(false);

  const entry = catalog.workflows.find((item) => item.ns === namespace && item.wf === name);
  const requested = params.get("version") ?? "";
  const definitionQuery = useDefinition(namespace, name, requested || undefined);
  const definition = definitionQuery.data;
  const versionSet = new Set<string>([...(entry?.versions ?? []), ...(definition?.availableVersions ?? [])]);
  if (definition?.version) versionSet.add(definition.version);
  const versions = Array.from(versionSet).sort(compareVersionsDesc);
  const version = requested || definition?.version || versions[0] || "";

  useDocumentMeta(buildPageTitle([name, namespace]));

  const parsed = useMemo(() => parseWorkflowSource(definition?.source), [definition?.source]);
  const trigger = parsed.trigger;

  const timeFrom = useMemo(() => periodFrom("24h"), []);
  const { counts } = useStatusCounts({ namespace, name, version: version || undefined, timeFrom });

  return (
    <div className="flex min-h-[760px] w-full flex-col bg-lm-bg">
      <PageHeader title={name} prefix={namespace} trail={[{ label: "Definitions", onClick: () => navigate("/definitions") }]} />
      <div className="flex flex-col gap-5" style={{ padding: pagePad }}>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <Dropdown
            label="Version"
            listLabel="Version"
            value={version}
            valueLabel={version || "…"}
            options={versions.map((item) => ({ value: item, label: item }))}
            onChange={(value) => setParams({ version: value }, { replace: true })}
            className="[&_[role=listbox]]:min-w-[200px]"
          />
          <button
            type="button"
            onClick={() => setStartOpen(true)}
            disabled={!definition}
            className="flex min-h-11 cursor-pointer items-center gap-2 rounded-lg border border-lm-line-strong bg-lm-panel px-4 text-[15px] font-semibold text-lm-text disabled:opacity-55"
          >
            <PlayIcon />
            {trigger.kind === "manual" ? "Start instance" : "Start manually"}
          </button>
        </div>

        {definitionQuery.isError && !definition && (
          <ErrorBlock
            text={`The definition ${namespace} / ${name}${requested ? ` v${requested}` : ""} could not be loaded.`}
            onRetry={() => void definitionQuery.refetch()}
          />
        )}

        <div className="flex flex-wrap items-stretch gap-4">
          {trigger.kind !== "manual" && (
            <div className="flex min-w-0 flex-[999_1_300px]">
              <TriggerCard trigger={trigger} />
            </div>
          )}
          <section
            aria-label="Instances in the last 24 hours"
            className="flex flex-[1_1_320px] flex-col gap-3 rounded-[14px] border border-lm-line bg-lm-panel px-5 py-4 shadow-lm-card"
          >
            <p className="m-0 text-[13px] font-semibold uppercase tracking-[0.06em] text-lm-muted">
              Instances
            </p>
            <div className="grid grid-cols-3 gap-2">
              {STAT_STATUSES.map((status) => {
                const n = counts?.[status] ?? 0;
                const alarm = status === "faulted" && n > 0;
                const label = STATUS_LABEL[status];
                return (
                  <button
                    key={status}
                    type="button"
                    aria-label={`See ${label.toLowerCase()} instances of ${name} v${version}`}
                    onClick={() => seeInstances({ ns: namespace, wf: name, ver: version, statuses: [status], period: "24h" })}
                    className={cn(
                      "flex min-w-0 cursor-pointer flex-col items-start gap-0.5 rounded-[10px] border px-3.5 py-2.5 text-left",
                      alarm ? "border-lm-fail-line bg-lm-fail-soft" : "border-lm-line bg-lm-panel hover:border-lm-line-strong",
                    )}
                  >
                    <span
                      className={cn(
                        "text-[26px] font-bold leading-[1.2] tabular-nums",
                        alarm ? "text-lm-fail" : n === 0 ? "text-lm-line-strong" : "text-lm-text",
                      )}
                    >
                      {counts ? n : "…"}
                    </span>
                    <span className={cn("text-sm font-medium", alarm ? "text-lm-fail" : "text-lm-muted")}>{label}</span>
                  </button>
                );
              })}
            </div>
          </section>
        </div>

        {definitionQuery.isLoading && <LoadingBlock label="Loading the definition" height={320} />}
        {definition && (
          <div className="flex flex-wrap items-start gap-6">
            <CodeBlock size="large" text={definition.source} ariaLabel="YAML source" className="min-w-0 flex-[999_1_520px]" />
          </div>
        )}
      </div>

      {startOpen && definition && (
        <StartDrawer
          ns={namespace}
          wf={name}
          version={version}
          trigger={trigger}
          onClose={() => setStartOpen(false)}
          onStarted={(id) => {
            setStartOpen(false);
            openInstance(id);
          }}
          onSeeInstances={() => {
            setStartOpen(false);
            seeInstances({ ns: namespace, wf: name, ver: version });
          }}
        />
      )}
    </div>
  );
}
