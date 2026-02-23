import { useEffect } from "react";

export type FaviconTone = "default" | "pending" | "running" | "completed" | "faulted" | "disconnected";

const ROOT_TITLE = "Lemline Dashboard";
const APP_SUFFIX = "Lemline";

const tonePalette: Record<FaviconTone, { bg: string; fg: string; dot: string; letter: string }> = {
  default: {
    bg: "#0f172a",
    fg: "#e2e8f0",
    dot: "#22d3ee",
    letter: "L",
  },
  pending: {
    bg: "#334155",
    fg: "#f8fafc",
    dot: "#94a3b8",
    letter: "P",
  },
  running: {
    bg: "#1d4ed8",
    fg: "#eff6ff",
    dot: "#38bdf8",
    letter: "R",
  },
  completed: {
    bg: "#047857",
    fg: "#ecfdf5",
    dot: "#34d399",
    letter: "C",
  },
  faulted: {
    bg: "#b91c1c",
    fg: "#fef2f2",
    dot: "#fca5a5",
    letter: "F",
  },
  disconnected: {
    bg: "#854d0e",
    fg: "#fffbeb",
    dot: "#fcd34d",
    letter: "D",
  },
};

const faviconCache = new Map<FaviconTone, string>();

export function buildPageTitle(parts: Array<string | null | undefined>): string {
  const normalized = parts
    .map((part) => part?.trim())
    .filter((part): part is string => Boolean(part));

  return normalized.length > 0
    ? `${normalized.join(" \u2014 ")} \u2014 ${APP_SUFFIX}`
    : ROOT_TITLE;
}

export function useDocumentMeta(title: string, faviconTone: FaviconTone = "default") {
  useEffect(() => {
    document.title = title;
    applyFavicon(faviconTone);
  }, [faviconTone, title]);
}

function applyFavicon(tone: FaviconTone) {
  const href = buildFaviconDataUri(tone);

  setFaviconLink("icon", href);
  setFaviconLink("shortcut icon", href);
}

function setFaviconLink(rel: string, href: string) {
  let link = document.head.querySelector<HTMLLinkElement>(`link[rel="${rel}"]`);
  if (!link) {
    link = document.createElement("link");
    link.rel = rel;
    document.head.appendChild(link);
  }

  link.type = "image/svg+xml";
  link.href = href;
}

function buildFaviconDataUri(tone: FaviconTone): string {
  const cached = faviconCache.get(tone);
  if (cached) return cached;

  const palette = tonePalette[tone];
  const svg = `
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
      <rect x="2" y="2" width="60" height="60" rx="14" fill="${palette.bg}" />
      <rect x="2" y="2" width="60" height="60" rx="14" fill="none" stroke="rgba(255,255,255,0.22)" stroke-width="2" />
      <text x="21" y="41" font-family="ui-sans-serif, system-ui, -apple-system, Segoe UI, sans-serif" font-size="26" font-weight="800" fill="${palette.fg}">
        ${palette.letter}
      </text>
      <circle cx="49" cy="17" r="6" fill="${palette.dot}" />
    </svg>
  `.trim();

  const encoded = `data:image/svg+xml,${encodeURIComponent(svg)}`;
  faviconCache.set(tone, encoded);
  return encoded;
}
