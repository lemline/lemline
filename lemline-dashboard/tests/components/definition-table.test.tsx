import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { DefinitionTable, type BreakdownRow } from "../../src/components/definitions/definition-table";

vi.mock("@tanstack/react-virtual", () => ({
  useVirtualizer: vi.fn((options: { count: number }) => ({
    getVirtualItems: () =>
      Array.from({ length: Math.min(options.count, 5) }, (_, index) => ({
        index,
        key: index,
        start: index * 42,
        size: 42,
      })),
    getTotalSize: () => options.count * 42,
  })),
}));

function orderedLabels(candidates: string[]): string[] {
  return screen
    .getAllByRole("button")
    .filter((element) => candidates.some((candidate) => (element.textContent ?? "").includes(candidate)))
    .map((element) => candidates.find((candidate) => (element.textContent ?? "").includes(candidate)) ?? "")
    .filter(Boolean);
}

describe("DefinitionTable", () => {
  it("sorts count columns and triggers row click", () => {
    const onRowClick = vi.fn();
    const rows: BreakdownRow[] = [
      { key: "alpha", label: "Alpha", started: 5, completed: 3, faulted: 1, running: 1 },
      { key: "beta", label: "Beta", started: 15, completed: 10, faulted: 2, running: 3 },
      { key: "gamma", label: "Gamma", started: 9, completed: 7, faulted: 1, running: 1 },
    ];

    render(<DefinitionTable title="Breakdown" rows={rows} onRowClick={onRowClick} />);

    fireEvent.click(screen.getByRole("button", { name: "Sort by Started" }));
    expect(orderedLabels(["Alpha", "Beta", "Gamma"])).toEqual(["Beta", "Gamma", "Alpha"]);

    const gammaRow = screen.getByRole("button", { name: /Gamma/ });
    fireEvent.click(gammaRow);
    expect(onRowClick).toHaveBeenCalledWith(rows[2]);
  });

  it("uses non-virtualized rendering at 50 rows or fewer", () => {
    const rows = Array.from({ length: 50 }, (_, index) => ({
      key: `row-${index + 1}`,
      label: `Row ${String(index + 1).padStart(3, "0")}`,
      started: index + 1,
      completed: index,
      faulted: 0,
      running: 1,
    }));

    render(<DefinitionTable title="Breakdown" rows={rows} />);

    expect(screen.getByText("Row 001")).toBeInTheDocument();
    expect(screen.getByText("Row 050")).toBeInTheDocument();
    const virtualHeightContainer = Array.from(document.querySelectorAll("div")).find(
      (element) => element.style.height === `${rows.length * 42}px`,
    );
    expect(virtualHeightContainer).toBeUndefined();
  });

  it("switches to virtualized rendering above 50 rows", () => {
    const rows = Array.from({ length: 55 }, (_, index) => ({
      key: `row-${index + 1}`,
      label: `Row ${String(index + 1).padStart(3, "0")}`,
      started: index + 1,
      completed: index,
      faulted: 0,
      running: 1,
    }));

    render(<DefinitionTable title="Breakdown" rows={rows} />);

    expect(screen.getByText("Row 001")).toBeInTheDocument();
    expect(screen.queryByText("Row 055")).not.toBeInTheDocument();

    const virtualHeightContainer = Array.from(document.querySelectorAll("div")).find(
      (element) => element.style.height === `${rows.length * 42}px`,
    );
    expect(virtualHeightContainer).toBeDefined();

    expect(screen.getByText("Row 001")).toBeInTheDocument();
  });
});
