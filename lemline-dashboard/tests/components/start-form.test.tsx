import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { toast } from "sonner";
import { StartForm } from "../../src/components/workflow/start-form";

const mockNavigate = vi.fn();
const mockStartInstance = vi.fn();

vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual<typeof import("react-router-dom")>("react-router-dom");
  return { ...actual, useNavigate: () => mockNavigate };
});

vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn() } }));
vi.mock("../../src/hooks/use-namespaces", () => ({
  useNamespaces: () => ({ data: ["team-a"], isLoading: false, isError: false }),
}));
vi.mock("../../src/hooks/use-definitions", () => ({
  useDefinitions: () => ({ data: [{ name: "orders", version: "1.0.0" }] }),
}));
vi.mock("../../src/lib/grpc-client", () => ({
  gatewayClient: { startInstance: (...args: unknown[]) => mockStartInstance(...args) },
}));

function renderForm() {
  render(
    <MemoryRouter>
      <StartForm initialNamespace="team-a" initialName="orders" initialVersion="1.0.0" />
    </MemoryRouter>,
  );
}

describe("StartForm", () => {
  beforeEach(() => vi.clearAllMocks());

  it("confirms a cron schedule without navigating to a nonexistent instance", async () => {
    mockStartInstance.mockResolvedValue({
      instanceId: "01956f7a-7777-7777-8777-777777777777",
      namespace: "team-a",
      name: "orders",
      scheduleOnly: true,
      nextRunAt: { seconds: BigInt(Math.floor(Date.now() / 1000) + 3600), nanos: 0 },
    });
    renderForm();

    fireEvent.click(screen.getByRole("button", { name: "Start workflow" }));

    const confirmation = await screen.findByRole("status");
    expect(confirmation).toHaveTextContent("Workflow scheduled");
    expect(confirmation).toHaveTextContent("next run at");
    expect(screen.getByRole("link", { name: "View workflow instances" })).toHaveAttribute(
      "href", "/ns/team-a/name/orders",
    );
    expect(mockNavigate).not.toHaveBeenCalled();
    expect(toast.success).toHaveBeenCalledWith("Workflow scheduled", expect.any(Object));
  });

  it("keeps the instance navigation for an immediate start", async () => {
    mockStartInstance.mockResolvedValue({
      instanceId: "01956f7a-7777-7777-8777-777777777777",
      namespace: "team-a",
      name: "orders",
      scheduleOnly: false,
    });
    renderForm();

    fireEvent.click(screen.getByRole("button", { name: "Start workflow" }));

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith("Workflow started", expect.any(Object)));
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(mockNavigate).not.toHaveBeenCalled();
  });
});
