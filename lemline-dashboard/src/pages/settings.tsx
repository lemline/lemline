import { Code, ConnectError } from "@connectrpc/connect";
import type { ReactNode } from "react";
import { ErrorBlock, LoadingBlock } from "../components/lm/empty-state";
import { CopyIcon } from "../components/lm/icons";
import { PageHeader, SectionTitle } from "../components/lm/page-header";
import type { ConfigFile } from "../gen/proto/lemline/gateway/v1/gateway_pb";
import { useCopy } from "../hooks/use-copy";
import { buildPageTitle, useDocumentMeta } from "../hooks/use-document-meta";
import { useGatewayConfig } from "../hooks/use-gateway-config";
import { usePagePadding } from "../hooks/use-layout";
import { useViewportWidth } from "../hooks/use-viewport-width";
import { DEFAULT_GATEWAY_URL, getRuntimeConfig } from "../lib/runtime-config";
import {
  fileLines,
  fileName,
  locatorLabel,
  outsideFileRows,
  type OriginKind,
  type OutsideRow,
} from "../lib/settings";
import { cn } from "../lib/utils";

const COLUMNS = "minmax(180px,1fr) minmax(220px,1.4fr) minmax(200px,1fr)";

/** Chip colors per origin, matching the line markers in the file. */
const KIND_STYLE: Record<OriginKind, { chip: string; bar: string; row: string }> = {
  cli: { chip: "border-lm-run-line bg-lm-run-soft text-lm-run-strong", bar: "var(--lm-run)", row: "bg-lm-run-soft" },
  env: {
    chip: "border-lm-warn-line bg-[var(--lm-event-soft)] text-lm-event-text",
    bar: "var(--lm-event)",
    row: "bg-[var(--lm-event-soft)]",
  },
  file: { chip: "border-transparent bg-lm-accent-soft text-lm-accent", bar: "var(--lm-accent)", row: "" },
  app: { chip: "border-lm-line bg-lm-subtle2 text-lm-text2", bar: "var(--lm-line-strong)", row: "" },
  default: { chip: "border-lm-line-strong bg-transparent text-lm-muted", bar: "var(--lm-line-strong)", row: "" },
};

const DEFAULT_FILE_PLACES = "--config, LEMLINE_CONFIG, ./.lemline.yaml, ~/.config/lemline/config.yaml, ~/.lemline.yaml";

export function SettingsPage() {
  useDocumentMeta(buildPageTitle(["Settings"]));
  const pagePad = usePagePadding();
  const wide = useViewportWidth() >= 900;
  const runtime = getRuntimeConfig();
  const gateway = useGatewayConfig();
  const file = gateway.data?.configFile;
  const outside = gateway.data ? outsideFileRows(gateway.data.settings, file) : [];

  return (
    <div className="flex min-h-[760px] w-full flex-col bg-lm-bg">
      <PageHeader title="Settings" />
      <div className="flex max-w-[1040px] flex-col gap-8" style={{ padding: pagePad }}>
        <Section title="Dashboard" sub="Where the dashboard sends its requests. Read once, when the page loads.">
          <Panel>
            {wide && <HeadRow />}
            <Row
              wide={wide}
              label="Gateway URL"
              name="gatewayBaseUrl"
              value={<span className="font-mono text-[13px]">{runtime.gatewayBaseUrl}</span>}
              note={runtime.gatewayBaseUrlSource === "config" ? `Default when not set: ${DEFAULT_GATEWAY_URL}` : ""}
              kind={runtime.gatewayBaseUrlSource === "config" ? "file" : "default"}
              origin={runtime.gatewayBaseUrlSource === "config" ? "config.js" : "Default"}
              originNote={
                runtime.gatewayBaseUrlSource === "config" ? "Served with the dashboard, at /config.js" : "No value in /config.js"
              }
            />
          </Panel>
        </Section>

        {gateway.isPending && <LoadingBlock label="Loading the gateway configuration" height={320} />}
        {gateway.isError && (
          <ErrorBlock text={errorText(gateway.error, runtime.gatewayBaseUrl)} onRetry={() => void gateway.refetch()} />
        )}

        {gateway.data && (
          <Section
            title={file ? fileName(file.path) : "Configuration file"}
            sub="The configuration file the gateway loaded, as written. Passwords are hidden."
          >
            {file ? (
              <FilePanel file={file} />
            ) : (
              <p className="m-0 rounded-[14px] border border-dashed border-lm-line-strong bg-lm-panel px-6 py-5 text-sm leading-normal text-lm-muted">
                The gateway runs without a configuration file. Lemline looks for one in: {DEFAULT_FILE_PLACES}.
              </p>
            )}
          </Section>
        )}

        {outside.length > 0 && (
          <Section
            title="Set outside the file"
            sub="Settings the gateway received from its environment or command line. They win over the configuration file."
          >
            <Panel>
              {wide && <HeadRow />}
              {outside.map((row) => (
                <OutsideFileRow key={row.key} row={row} wide={wide} />
              ))}
            </Panel>
          </Section>
        )}
      </div>
    </div>
  );
}

function errorText(error: unknown, url: string): string {
  const code = error instanceof ConnectError ? error.code : undefined;
  if (code === Code.PermissionDenied) {
    return "Your access does not allow reading the gateway configuration. It needs the lemline.config scope.";
  }
  if (code === Code.Unimplemented) {
    return "This gateway version does not report its configuration.";
  }
  return `Could not reach the gateway at ${url}.`;
}

function Section({ title, sub, children }: { title: string; sub: string; children: ReactNode }) {
  return (
    <section aria-label={title} className="flex flex-col gap-3">
      <div className="flex flex-col gap-1">
        <SectionTitle>{title}</SectionTitle>
        <p className="m-0 text-sm leading-normal text-lm-muted">{sub}</p>
      </div>
      {children}
    </section>
  );
}

function Panel({ children }: { children: ReactNode }) {
  return <div className="overflow-hidden rounded-[14px] border border-lm-line bg-lm-panel shadow-lm-card">{children}</div>;
}

function HeadRow() {
  return (
    <div
      className="grid gap-4 border-b-2 border-lm-line-strong bg-lm-panel px-5 py-2.5 text-[13px] font-semibold text-lm-muted"
      style={{ gridTemplateColumns: COLUMNS }}
    >
      <span>Setting</span>
      <span>Value</span>
      <span>Origin</span>
    </div>
  );
}

function Row({
  wide,
  label,
  name,
  value,
  note,
  kind,
  origin,
  originNote,
}: {
  wide: boolean;
  label: string;
  name: string;
  value: ReactNode;
  note: string;
  kind: OriginKind;
  origin: string;
  originNote: string;
}) {
  return (
    <div
      className={cn(
        "border-b border-lm-line-soft text-sm last:border-b-0",
        wide ? "grid items-start gap-4 px-5 py-3.5" : "flex flex-col gap-2.5 px-4 py-3.5",
      )}
      style={wide ? { gridTemplateColumns: COLUMNS } : undefined}
    >
      <span className="flex min-w-0 flex-col gap-0.5">
        <span className="text-[15px] font-semibold [overflow-wrap:anywhere]">{label}</span>
        <code className="font-mono text-xs text-lm-muted [overflow-wrap:anywhere]">{name}</code>
      </span>
      <span className="flex min-w-0 flex-col gap-0.5 [overflow-wrap:anywhere]">
        {value}
        {note && <span className="text-[13px] text-lm-muted">{note}</span>}
      </span>
      <span className="flex min-w-0 flex-col items-start gap-1">
        <span
          className={cn(
            "inline-flex items-center gap-1.5 whitespace-nowrap rounded-full border px-2.5 py-[3px] text-[13px] font-semibold",
            KIND_STYLE[kind].chip,
          )}
        >
          {origin}
        </span>
        {originNote && <span className="text-[13px] text-lm-muted [overflow-wrap:anywhere]">{originNote}</span>}
      </span>
    </div>
  );
}

function OutsideFileRow({ row, wide }: { row: OutsideRow; wide: boolean }) {
  return (
    <Row
      wide={wide}
      label={row.label}
      name={row.key}
      value={
        <span className={row.secret ? "italic text-lm-muted" : "font-mono text-[13px]"}>{row.value}</span>
      }
      note={row.relation}
      kind={row.kind}
      origin={row.origin}
      originNote={row.name}
    />
  );
}

function FilePanel({ file }: { file: ConfigFile }) {
  const { copied, copy } = useCopy(file.text ?? "");
  const lines = fileLines(file);
  const meta = [locatorLabel(file.locatedBy), file.text !== undefined ? `${lines.length} lines` : ""]
    .filter(Boolean)
    .join(" · ");
  return (
    <Panel>
      <div className="flex flex-wrap items-center justify-between gap-2.5 border-b border-lm-line py-3 pl-5 pr-4">
        <span className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1.5">
          <code className="font-mono text-[13px] font-medium text-lm-text [overflow-wrap:anywhere]">{file.path}</code>
          {meta && <span className="text-[13px] text-lm-muted">{meta}</span>}
        </span>
        {file.text !== undefined && (
          <button
            type="button"
            onClick={() => void copy()}
            className="flex min-h-10 cursor-pointer items-center gap-1.5 rounded-lg border border-lm-line-strong bg-lm-panel px-3 text-sm font-medium text-lm-text"
          >
            <CopyIcon />
            {copied ? "Copied" : "Copy"}
          </button>
        )}
      </div>
      {file.text === undefined ? (
        <p className="m-0 px-5 py-4 text-sm text-lm-muted">This file is too large to show here.</p>
      ) : (
        <div className="overflow-x-auto bg-lm-subtle py-3">
          <div className="min-w-max font-mono text-[13px] leading-[1.7]">
            {lines.map((line) => {
              const over = line.overriddenBy;
              const style = over ? KIND_STYLE[over.kind] : undefined;
              return (
                <div
                  key={line.number}
                  className={cn("flex items-center gap-3.5 pr-5", style?.row)}
                  style={style ? { boxShadow: `inset 3px 0 0 ${style.bar}` } : undefined}
                >
                  <span aria-hidden="true" className="w-11 shrink-0 select-none text-right text-lm-muted opacity-70">
                    {line.number}
                  </span>
                  <span className={cn("whitespace-pre", line.comment || line.secret ? "text-lm-muted" : "text-lm-text")}>
                    {line.indent}
                    <span className={over ? "line-through" : undefined}>{line.body}</span>
                  </span>
                  {over && (
                    <span
                      className={cn(
                        "inline-flex items-center whitespace-nowrap rounded-full border bg-lm-panel px-2 py-px font-sans text-xs font-semibold",
                        style?.chip,
                      )}
                    >
                      {over.label}
                    </span>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      )}
    </Panel>
  );
}
