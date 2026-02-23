import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { InstanceTable } from "../../src/components/instances/instance-table";
import { toast } from "sonner";

vi.mock("sonner", () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

interface InstanceRowFixture {
  workflowId: string;
  namespace: string;
  name: string;
  version: string;
  status: string;
  startedAt?: string;
  durationMs?: number;
  error?: {
    title: string;
  };
}

function getOrderedNames(expectedNames: string[]): string[] {
  return screen
    .getAllByRole("row")
    .slice(1)
    .map((row) =>
      expectedNames.find((name) => within(row).queryByText(name)) ?? "",
    )
    .filter(Boolean);
}

describe("InstanceTable", () => {
  const rows: InstanceRowFixture[] = [
    {
      workflowId: "01956f7a-1111-7111-8111-111111111111",
      namespace: "sales",
      name: "Alpha",
      version: "1.0.0",
      status: "COMPLETED",
      startedAt: "2025-01-01T10:00:00.000Z",
      durationMs: 3000,
    },
    {
      workflowId: "01956f7a-2222-7222-8222-222222222222",
      namespace: "sales",
      name: "Beta",
      version: "1.0.1",
      status: "RUNNING",
      startedAt: "2025-01-01T12:00:00.000Z",
      durationMs: 1000,
    },
    {
      workflowId: "01956f7a-3333-7333-8333-333333333333",
      namespace: "ops",
      name: "Gamma",
      version: "2.0.0",
      status: "FAULTED",
      startedAt: "2025-01-01T11:00:00.000Z",
      durationMs: 5000,
      error: {
        title: "Task faulted",
      },
    },
  ];

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("sorts by Started desc by default and toggles Duration sorting", () => {
    render(
      <InstanceTable
        rows={rows}
        onRowClick={() => {}}
        hasMore={false}
        isLoadingMore={false}
        onLoadMore={() => {}}
      />,
    );

    expect(getOrderedNames(["Alpha", "Beta", "Gamma"])).toEqual([
      "Beta",
      "Gamma",
      "Alpha",
    ]);

    fireEvent.click(screen.getByRole("button", { name: "Sort by Duration" }));
    expect(getOrderedNames(["Alpha", "Beta", "Gamma"])).toEqual([
      "Gamma",
      "Alpha",
      "Beta",
    ]);

    fireEvent.click(screen.getByRole("button", { name: "Sort by Duration" }));
    expect(getOrderedNames(["Alpha", "Beta", "Gamma"])).toEqual([
      "Beta",
      "Alpha",
      "Gamma",
    ]);
  });

  it("copies workflow ID without triggering row navigation and resets indicator after 300ms", async () => {
    const onRowClick = vi.fn();
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText },
    });

    render(
      <InstanceTable
        rows={rows}
        onRowClick={onRowClick}
        hasMore={false}
        isLoadingMore={false}
        onLoadMore={() => {}}
      />,
    );

    const copyButton = screen.getAllByRole("button", { name: "Copy workflow ID" })[0];
    fireEvent.click(copyButton);

    expect(writeText).toHaveBeenCalledWith(rows[1].workflowId);
    expect(onRowClick).not.toHaveBeenCalled();
    await waitFor(() => {
      expect(toast.success).toHaveBeenCalledWith("Copied to clipboard", { duration: 2000 });
      expect(copyButton).toHaveClass("text-emerald-600");
    });

    await act(async () => {
      await new Promise((resolve) => window.setTimeout(resolve, 320));
    });

    expect(copyButton).not.toHaveClass("text-emerald-600");
  });

  it("shows remaining count in load more button when totalCount is available", () => {
    render(
      <InstanceTable
        rows={rows}
        onRowClick={() => {}}
        hasMore
        isLoadingMore={false}
        onLoadMore={() => {}}
        totalCount={10}
      />,
    );

    expect(screen.getByRole("button", { name: "Load more (7 remaining)" })).toBeInTheDocument();
  });
});
