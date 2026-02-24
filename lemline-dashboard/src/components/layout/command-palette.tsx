import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { Search } from "lucide-react";
import { gatewayClient } from "../../lib/grpc-client";
import { useNamespaces } from "../../hooks/use-namespaces";
import { ListDefinitionsRequest } from "../../gen/proto/lemline/gateway/v1/workflow_gateway_pb";
import { Input } from "../ui/input";
import { Button } from "../ui/button";

interface CommandPaletteProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

interface RecentNavigation {
  label: string;
  path: string;
}

type PaletteSection = "Recent" | "Actions" | "Namespaces" | "Definitions";

interface PaletteItem {
  id: string;
  label: string;
  section: PaletteSection;
  description?: string;
  disabled?: boolean;
  action: () => void;
}

const RECENTS_STORAGE_KEY = "lemline.dashboard.recents";
const MAX_RECENTS = 8;

export function CommandPalette({ open, onOpenChange }: CommandPaletteProps) {
  const navigate = useNavigate();
  const { data: namespaces = [] } = useNamespaces();
  const [query, setQuery] = useState("");
  const [highlightedIndex, setHighlightedIndex] = useState(0);
  const [recentNavigations, setRecentNavigations] = useState<RecentNavigation[]>(() => loadRecentNavigations());

  const definitionsQuery = useQuery({
    queryKey: ["command-palette-definitions", namespaces],
    enabled: open && namespaces.length > 0,
    staleTime: 5 * 60_000,
    queryFn: async () => {
      const responses = await Promise.all(
        namespaces.map((namespace) =>
          gatewayClient.listDefinitions(
            new ListDefinitionsRequest({
              namespace,
              latestOnly: true,
            }),
          ),
        ),
      );

      return responses
        .flatMap((response) => response.definitions)
        .map((definition) => ({
          namespace: definition.namespace,
          name: definition.name,
          version: definition.version,
        }))
        .sort((left, right) =>
          `${left.name}/${left.namespace}/${left.version}`.localeCompare(
            `${right.name}/${right.namespace}/${right.version}`,
          ),
        );
    },
  });

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        const nextOpen = !open;
        onOpenChange(nextOpen);
        if (nextOpen) {
          setHighlightedIndex(0);
        }
      }
      if (event.key === "Escape") {
        onOpenChange(false);
      }
    };

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [open, onOpenChange]);

  function recordRecentNavigation(entry: RecentNavigation) {
    setRecentNavigations((previous) => {
      const next = [entry, ...previous.filter((item) => item.path !== entry.path)].slice(0, MAX_RECENTS);
      persistRecentNavigations(next);
      return next;
    });
  }

  function navigateWithRecent(path: string, label: string) {
    recordRecentNavigation({ path, label });
    navigate(path);
  }

  const items: PaletteItem[] = (() => {
    const tokens = toSearchTokens(query);
    const queryText = query.trim();
    const hasQuery = queryText.length > 0;

    const actionItems: PaletteItem[] = [
      {
        id: "start-workflow",
        section: "Actions",
        label: "Start workflow",
        description: "Open workflow start form",
        action: () => navigateWithRecent("/start", "Start workflow"),
      },
      {
        id: "jump-to-instance",
        section: "Actions",
        label: hasQuery ? `Jump to instance: ${queryText}` : "Jump to instance",
        description: hasQuery ? "Open instance detail page" : "Type a workflow ID to jump",
        disabled: !hasQuery,
        action: () => {
          if (!hasQuery) return;
          navigateWithRecent(`/id/${queryText}`, `Instance ${queryText}`);
        },
      },
    ];

    const recentItems: PaletteItem[] = recentNavigations
      .filter((entry) => matchesTokens(`${entry.label} ${entry.path}`, tokens))
      .map((entry) => ({
        id: `recent:${entry.path}`,
        section: "Recent",
        label: entry.label,
        description: entry.path,
        action: () => navigateWithRecent(entry.path, entry.label),
      }));

    const namespaceItems: PaletteItem[] = namespaces
      .filter((namespace) => matchesTokens(namespace, tokens))
      .map((namespace) => ({
        id: `namespace:${namespace}`,
        section: "Namespaces",
        label: namespace,
        description: "Go to namespace",
        action: () => navigateWithRecent(`/ns/${encodeURIComponent(namespace)}`, `Namespace ${namespace}`),
      }));

    const definitions = definitionsQuery.data ?? [];
    const definitionItems: PaletteItem[] = hasQuery
      ? definitions
          .filter((definition) =>
            matchesTokens(`${definition.name} ${definition.namespace} ${definition.version}`, tokens),
          )
          .slice(0, 40)
          .map((definition) => {
            const path = `/ns/${encodeURIComponent(definition.namespace)}/name/${encodeURIComponent(definition.name)}/version/${encodeURIComponent(definition.version)}`;
            return {
              id: `definition:${definition.namespace}/${definition.name}/${definition.version}`,
              section: "Definitions",
              label: `${definition.name} (${definition.namespace})`,
              description: `v${definition.version}`,
              action: () =>
                navigateWithRecent(
                  path,
                  `Definition ${definition.name} (${definition.namespace}) v${definition.version}`,
                ),
            } satisfies PaletteItem;
          })
      : [];

    const loadingDefinitionsItem: PaletteItem[] =
      hasQuery && definitionsQuery.isFetching
        ? [
            {
              id: "definitions-loading",
              section: "Definitions",
              label: "Loading definitions...",
              disabled: true,
              action: () => {},
            },
          ]
        : [];

    return [...recentItems, ...actionItems, ...namespaceItems, ...definitionItems, ...loadingDefinitionsItem];
  })();

  const groups = groupItems(items);
  const activeIndex = clampIndex(highlightedIndex, items.length);

  if (!open) return null;

  const executeItem = (item: PaletteItem) => {
    if (item.disabled) return;
    item.action();
    onOpenChange(false);
    setQuery("");
    setHighlightedIndex(0);
  };

  return (
    <div
      className="fixed inset-0 z-40 bg-slate-950/60 p-4"
      onClick={() => onOpenChange(false)}
      role="presentation"
    >
      <div
        className="mx-auto mt-24 w-full max-w-2xl rounded-xl border border-slate-200 bg-white p-4 shadow-xl dark:border-slate-700 dark:bg-slate-900"
        onClick={(event) => event.stopPropagation()}
        role="dialog"
        aria-modal="true"
      >
        <div className="mb-3 flex items-center gap-2">
          <Search className="h-4 w-4 text-slate-500" />
          <Input
            autoFocus
            value={query}
            onChange={(event) => {
              setQuery(event.target.value);
              setHighlightedIndex(0);
            }}
            onKeyDown={(event) => {
              if (event.key === "ArrowDown") {
                event.preventDefault();
                setHighlightedIndex((current) => nextEnabledIndex(items, current, 1));
                return;
              }

              if (event.key === "ArrowUp") {
                event.preventDefault();
                setHighlightedIndex((current) => nextEnabledIndex(items, current, -1));
                return;
              }

              if (event.key === "Enter") {
                event.preventDefault();
                const item = items[activeIndex];
                if (item) executeItem(item);
              }
            }}
            placeholder="Search commands, namespace, definition, or workflow ID"
          />
        </div>

        <div className="max-h-80 space-y-3 overflow-y-auto">
          {groups.map((group) => (
            <div key={group.section}>
              <p className="px-2 pb-1 text-xs font-semibold uppercase tracking-wide text-slate-500">
                {group.section}
              </p>
              <div className="space-y-1">
                {group.items.map(({ item, index }) => (
                  <Button
                    key={item.id}
                    variant="ghost"
                    disabled={item.disabled}
                    className={`h-auto w-full justify-between py-2 text-left ${
                      index === activeIndex ? "bg-slate-100 dark:bg-slate-800" : ""
                    }`}
                    onMouseEnter={() => setHighlightedIndex(index)}
                    onClick={() => executeItem(item)}
                  >
                    <span className="truncate">{item.label}</span>
                    {item.description && (
                      <span className="ml-3 shrink-0 text-xs text-slate-500">{item.description}</span>
                    )}
                  </Button>
                ))}
              </div>
            </div>
          ))}

          {items.length === 0 && <p className="px-2 py-3 text-sm text-slate-500">No matching commands.</p>}
        </div>
      </div>
    </div>
  );
}

function toSearchTokens(value: string): string[] {
  return value
    .trim()
    .toLowerCase()
    .split(/\s+/)
    .filter(Boolean);
}

function matchesTokens(haystack: string, tokens: string[]): boolean {
  if (tokens.length === 0) return true;
  const normalized = haystack.toLowerCase();
  return tokens.every((token) => normalized.includes(token));
}

function groupItems(items: PaletteItem[]) {
  const order: PaletteSection[] = ["Recent", "Actions", "Namespaces", "Definitions"];
  return order
    .map((section) => ({
      section,
      items: items
        .map((item, index) => ({ item, index }))
        .filter(({ item }) => item.section === section),
    }))
    .filter((group) => group.items.length > 0);
}

function clampIndex(index: number, size: number): number {
  if (size === 0) return 0;
  if (index < 0) return 0;
  if (index >= size) return size - 1;
  return index;
}

function nextEnabledIndex(items: PaletteItem[], current: number, delta: 1 | -1): number {
  if (items.length === 0) return 0;

  let index = clampIndex(current, items.length);
  for (let step = 0; step < items.length; step += 1) {
    index = (index + delta + items.length) % items.length;
    if (!items[index].disabled) return index;
  }

  return clampIndex(current, items.length);
}

function loadRecentNavigations(): RecentNavigation[] {
  try {
    const raw = window.localStorage.getItem(RECENTS_STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return [];

    return parsed
      .filter((entry): entry is RecentNavigation => {
        return Boolean(
          entry &&
            typeof entry === "object" &&
            "label" in entry &&
            "path" in entry &&
            typeof (entry as { label: unknown }).label === "string" &&
            typeof (entry as { path: unknown }).path === "string",
        );
      })
      .slice(0, MAX_RECENTS);
  } catch {
    return [];
  }
}

function persistRecentNavigations(value: RecentNavigation[]) {
  try {
    window.localStorage.setItem(RECENTS_STORAGE_KEY, JSON.stringify(value));
  } catch {
    // Ignore storage failures.
  }
}
