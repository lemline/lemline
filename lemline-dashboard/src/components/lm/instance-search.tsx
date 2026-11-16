import { create } from "@bufbuild/protobuf";
import { useQuery } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { ListInstancesRequestSchema } from "../../gen/proto/lemline/gateway/v1/gateway_pb";
import { gatewayClient } from "../../lib/grpc-client";
import { formatRelative, shortInstanceId } from "../../lib/format";
import { cn } from "../../lib/utils";
import { CloseIcon, SearchIcon } from "./icons";
import { STATUS_LABEL, uiStatusOf } from "../../lib/status";

const MIN_CHARS = 3;
const MAX_RESULTS = 4;
const RECENT_SCANNED = 500;

/** Normalizes what was typed or pasted into a piece of an instance ID. */
function idPrefixOf(text: string): string {
  return text.trim().toLowerCase().replace(/^.*\//, "").replace(/[^0-9a-f-]/g, "");
}

interface InstanceSearchProps {
  namespace?: string;
  onOpen: (id: string) => void;
}

/** "Find an instance by ID": suggests instances whose ID starts with, or contains, what is typed. */
export function InstanceSearch({ namespace, onOpen }: InstanceSearchProps) {
  const [query, setQuery] = useState("");
  const [debounced, setDebounced] = useState("");
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(idPrefixOf(query)), 200);
    return () => window.clearTimeout(timer);
  }, [query]);

  useEffect(() => {
    if (!open) return;
    const onPointer = (event: MouseEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onPointer);
    return () => document.removeEventListener("mousedown", onPointer);
  }, [open]);

  const prefix = idPrefixOf(query);
  const searchable = debounced.length >= MIN_CHARS;
  const results = useQuery({
    queryKey: ["instance-search", namespace, debounced],
    enabled: searchable,
    queryFn: async ({ signal }) =>
      (
        await gatewayClient.listInstances(
          create(ListInstancesRequestSchema, {
            namespace: namespace || undefined,
            instanceIdPrefix: debounced,
            pageSize: MAX_RESULTS,
          }),
          { signal },
        )
      ).instances,
  });

  // The gateway matches the start of an ID; a piece from the middle ("9b3e-5f1c") is looked for
  // among the most recent instances.
  const recent = useQuery({
    queryKey: ["instance-search-recent", namespace],
    enabled: searchable,
    staleTime: 10_000,
    queryFn: async ({ signal }) =>
      (
        await gatewayClient.listInstances(
          create(ListInstancesRequestSchema, { namespace: namespace || undefined, pageSize: RECENT_SCANNED }),
          { signal },
        )
      ).instances,
  });
  const flat = (value: string) => value.toLowerCase().replace(/[^0-9a-f]/g, "");
  const wanted = flat(debounced);
  const byPrefix = searchable && debounced === prefix ? (results.data ?? []) : [];
  const byPiece =
    searchable && debounced === prefix && wanted.length >= MIN_CHARS
      ? (recent.data ?? []).filter(
          (instance) =>
            flat(instance.instanceId).includes(wanted) && !byPrefix.some((item) => item.instanceId === instance.instanceId),
        )
      : [];
  const items = [...byPrefix, ...byPiece].slice(0, MAX_RESULTS);
  const hasQuery = query.trim().length > 0;
  const reset = () => {
    setQuery("");
    setOpen(false);
  };
  const go = (id: string) => {
    reset();
    onOpen(id);
  };

  let message = "";
  if (prefix.length < MIN_CHARS) message = "Type at least 3 characters of the instance ID.";
  else if ((results.isFetching || recent.isFetching) && items.length === 0) message = "Searching…";
  else if (items.length === 0 && searchable) message = `No instance has an ID containing “${query.trim()}”.`;

  return (
    <div ref={rootRef} className="relative min-w-0 max-w-[460px] flex-[1_1_280px]">
      <label
        className={cn(
          "flex min-h-11 items-center gap-2 rounded-lg border bg-lm-panel px-3 text-lm-muted",
          hasQuery ? "border-lm-accent" : "border-lm-line-strong",
        )}
      >
        <SearchIcon />
        <input
          aria-label="Find an instance by ID"
          placeholder="Find an instance by ID"
          value={query}
          onChange={(event) => {
            setQuery(event.target.value);
            setOpen(true);
          }}
          onFocus={() => setOpen(true)}
          onKeyDown={(event) => {
            if (event.key === "Enter" && items[0]) go(items[0].instanceId);
            if (event.key === "Escape") reset();
          }}
          className="min-w-0 grow border-0 bg-transparent font-sans text-sm text-lm-text outline-none placeholder:text-lm-muted"
        />
        {hasQuery && (
          <button
            type="button"
            aria-label="Clear search"
            onClick={reset}
            className="flex h-8 w-8 cursor-pointer items-center justify-center rounded-md border-0 bg-transparent text-lm-muted"
          >
            <CloseIcon />
          </button>
        )}
      </label>
      {hasQuery && open && (
        <div
          role="listbox"
          aria-label="Search results"
          className="absolute left-0 right-0 top-[50px] z-40 flex flex-col gap-0.5 rounded-[10px] border border-lm-line bg-lm-panel p-1.5 shadow-lm-pop"
        >
          {items.map((instance, index) => (
            <button
              key={instance.instanceId}
              type="button"
              role="option"
              aria-selected={index === 0}
              onClick={() => go(instance.instanceId)}
              className={cn(
                "flex min-h-11 cursor-pointer items-center gap-2.5 rounded-md border-0 px-2.5 py-1.5 text-left text-sm text-lm-text hover:bg-lm-subtle2",
                index === 0 ? "bg-lm-subtle2" : "bg-lm-panel",
              )}
            >
              <span className="w-[72px] shrink-0 text-xs font-semibold uppercase tracking-[0.04em] text-lm-muted">
                Instance
              </span>
              <span className="flex min-w-0 flex-col gap-px">
                <span className="font-mono font-semibold [overflow-wrap:anywhere]">{shortInstanceId(instance.instanceId)}</span>
                <span className="text-[13px] text-lm-muted">
                  {instance.namespace} / {instance.name} · {STATUS_LABEL[uiStatusOf(instance.status)].toLowerCase()} ·{" "}
                  {formatRelative(instance.startedAt ?? instance.createdAt)}
                </span>
              </span>
            </button>
          ))}
          {message && <p className="m-0 p-2.5 text-sm text-lm-muted">{message}</p>}
        </div>
      )}
    </div>
  );
}
