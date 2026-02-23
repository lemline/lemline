import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { type ReactNode, useState } from "react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { CommandPalette } from "../../src/components/layout/command-palette";

const RECENTS_STORAGE_KEY = "lemline.dashboard.recents";
const mockNavigate = vi.fn();
const mockUseNamespaces = vi.fn();
const mockListDefinitions = vi.fn();

vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual<typeof import("react-router-dom")>("react-router-dom");
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  };
});

vi.mock("../../src/hooks/use-namespaces", () => ({
  useNamespaces: () => mockUseNamespaces(),
}));

vi.mock("../../src/lib/grpc-client", () => ({
  gatewayClient: {
    listDefinitions: (...args: unknown[]) => mockListDefinitions(...args),
  },
}));

function renderWithProviders(ui: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  );
}

function PaletteHarness() {
  const [open, setOpen] = useState(false);
  return <CommandPalette open={open} onOpenChange={setOpen} />;
}

describe("CommandPalette", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();
    mockUseNamespaces.mockReturnValue({ data: ["orders"], isLoading: false });
    mockListDefinitions.mockResolvedValue({
      definitions: [
        {
          namespace: "orders",
          name: "OrderWorkflow",
          version: "1.0.0",
        },
      ],
    });
  });

  it("toggles open with Cmd/Ctrl+K and closes on Escape", async () => {
    renderWithProviders(<PaletteHarness />);

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    fireEvent.keyDown(window, { key: "k", metaKey: true });
    expect(screen.getByRole("dialog")).toBeInTheDocument();

    fireEvent.keyDown(window, { key: "Escape" });
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
  });

  it("jumps to an instance from the action item and records recent navigation", async () => {
    renderWithProviders(<CommandPalette open onOpenChange={() => {}} />);

    const query = "01956f7a-9999-7999-8999-999999999999";
    fireEvent.change(
      screen.getByPlaceholderText("Search commands, namespace, definition, or workflow ID"),
      { target: { value: query } },
    );

    fireEvent.click(screen.getByRole("button", { name: new RegExp(`Jump to instance: ${query}`) }));

    expect(mockNavigate).toHaveBeenCalledWith(`/id/${query}`);

    const persisted = JSON.parse(window.localStorage.getItem(RECENTS_STORAGE_KEY) ?? "[]");
    expect(persisted[0]).toEqual({
      label: `Instance ${query}`,
      path: `/id/${query}`,
    });
  });

  it("loads recent entries from localStorage and executes a recent navigation", () => {
    window.localStorage.setItem(
      RECENTS_STORAGE_KEY,
      JSON.stringify([
        {
          label: "Namespace orders",
          path: "/ns/orders",
        },
      ]),
    );

    renderWithProviders(<CommandPalette open onOpenChange={() => {}} />);

    expect(screen.getByText("Recent")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /Namespace orders/ }));
    expect(mockNavigate).toHaveBeenCalledWith("/ns/orders");
  });
});
