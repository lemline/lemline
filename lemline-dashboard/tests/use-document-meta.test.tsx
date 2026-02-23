import { render } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import { buildPageTitle, type FaviconTone, useDocumentMeta } from "../src/hooks/use-document-meta";

const emDash = String.fromCharCode(0x2014);

function MetaProbe({ title, tone = "default" }: { title: string; tone?: FaviconTone }) {
  useDocumentMeta(title, tone);
  return null;
}

describe("useDocumentMeta", () => {
  beforeEach(() => {
    document.title = "";
    document.head.querySelectorAll('link[rel="icon"], link[rel="shortcut icon"]').forEach((link) => link.remove());
  });

  it("builds a dashboard-scoped title", () => {
    expect(buildPageTitle(["orders", "Namespace"])).toBe(`orders ${emDash} Namespace ${emDash} Lemline`);
    expect(buildPageTitle([undefined, ""])).toBe("Lemline Dashboard");
  });

  it("sets document title and favicon links", () => {
    const { rerender } = render(<MetaProbe title={`Orders ${emDash} Lemline`} tone="running" />);

    expect(document.title).toBe(`Orders ${emDash} Lemline`);
    const iconHref = document.head.querySelector('link[rel="icon"]')?.getAttribute("href");
    const shortcutIconHref = document.head.querySelector('link[rel="shortcut icon"]')?.getAttribute("href");

    expect(iconHref).toContain("data:image/svg+xml");
    expect(shortcutIconHref).toContain("data:image/svg+xml");

    rerender(<MetaProbe title={`Orders ${emDash} Lemline`} tone="faulted" />);
    const nextIconHref = document.head.querySelector('link[rel="icon"]')?.getAttribute("href");
    expect(nextIconHref).not.toEqual(iconHref);
  });
});
