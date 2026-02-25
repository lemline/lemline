import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { CascadingSelects } from "../../src/components/layout/cascading-selects";

const mockUseNamespaces = vi.fn();
const mockUseDefinitions = vi.fn();
const mockUseInstanceTimeline = vi.fn();
const mockListInstances = vi.fn();

vi.mock("../../src/hooks/use-namespaces", () => ({
  useNamespaces: () => mockUseNamespaces(),
}));

vi.mock("../../src/hooks/use-definitions", () => ({
  useDefinitions: (options: unknown) => mockUseDefinitions(options),
}));

vi.mock("../../src/hooks/use-instance-timeline", () => ({
  useInstanceTimeline: () => mockUseInstanceTimeline(),
}));

vi.mock("../../src/lib/grpc-client", () => ({
  gatewayClient: {
    listInstances: (...args: unknown[]) => mockListInstances(...args),
  },
}));

function renderWithProviders(ui: ReactNode, path: string) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>{ui}</MemoryRouter>
    </QueryClientProvider>,
  );
}

function getScopeSelects() {
  const selects = screen.getAllByRole("combobox") as HTMLSelectElement[];
  expect(selects).toHaveLength(3);
  return {
    namespaceSelect: selects[0],
    nameSelect: selects[1],
    versionSelect: selects[2],
  };
}

function getOptionValues(select: HTMLSelectElement): string[] {
  return Array.from(select.options).map((option) => option.value);
}

describe("CascadingSelects", () => {
  beforeEach(() => {
    vi.clearAllMocks();

    mockUseNamespaces.mockReturnValue({
      data: ["examples", "orders"],
      isLoading: false,
      isError: false,
    });

    mockUseDefinitions.mockImplementation((rawOptions?: unknown) => {
      const options = rawOptions as { namespace?: string; name?: string };
      if (!options?.namespace) {
        return { data: [] };
      }

      if (!options.name) {
        return {
          data: [
            { name: "hello", version: "1.0.0" },
            { name: "invoice", version: "1.0.0" },
          ],
        };
      }

      return {
        data: [
          { name: options.name, version: "1.0.0" },
          { name: options.name, version: "2.0.0" },
        ],
      };
    });

    mockUseInstanceTimeline.mockReturnValue({ data: undefined });
    mockListInstances.mockResolvedValue({ instances: [] });
  });

  it("uses root defaults on /", () => {
    renderWithProviders(<CascadingSelects />, "/");
    const { namespaceSelect, nameSelect, versionSelect } = getScopeSelects();

    expect(namespaceSelect.value).toBe("all");
    expect(namespaceSelect).not.toBeDisabled();
    expect(nameSelect.value).toBe("all");
    expect(nameSelect).toBeDisabled();
    expect(versionSelect.value).toBe("all");
    expect(versionSelect).toBeDisabled();
  });

  it("activates the name selector on /ns/:namespace", () => {
    renderWithProviders(<CascadingSelects />, "/ns/examples");
    const { namespaceSelect, nameSelect, versionSelect } = getScopeSelects();

    expect(namespaceSelect.value).toBe("examples");
    expect(nameSelect.value).toBe("all");
    expect(nameSelect).not.toBeDisabled();
    expect(getOptionValues(nameSelect)).toEqual(["all", "hello", "invoice"]);
    expect(versionSelect.value).toBe("all");
    expect(versionSelect).toBeDisabled();
  });

  it("activates the version selector on /ns/:namespace/name/:name", () => {
    renderWithProviders(<CascadingSelects />, "/ns/examples/name/hello");
    const { namespaceSelect, nameSelect, versionSelect } = getScopeSelects();

    expect(namespaceSelect.value).toBe("examples");
    expect(nameSelect.value).toBe("hello");
    expect(versionSelect.value).toBe("all");
    expect(versionSelect).not.toBeDisabled();
    expect(getOptionValues(versionSelect)).toEqual(["all", "1.0.0", "2.0.0"]);
  });

  it("selects namespace/name/version on /ns/:namespace/name/:name/version/:version", () => {
    renderWithProviders(<CascadingSelects />, "/ns/examples/name/hello/version/1.0.0");
    const { namespaceSelect, nameSelect, versionSelect } = getScopeSelects();

    expect(namespaceSelect.value).toBe("examples");
    expect(nameSelect.value).toBe("hello");
    expect(versionSelect.value).toBe("1.0.0");
    expect(versionSelect).not.toBeDisabled();
    expect(getOptionValues(versionSelect)).toEqual(["all", "1.0.0", "2.0.0"]);
  });

  it("supports legacy /ns/:namespace/name/:name/v/:version URLs", () => {
    renderWithProviders(<CascadingSelects />, "/ns/examples/name/hello/v/2.0.0");
    const { namespaceSelect, nameSelect, versionSelect } = getScopeSelects();

    expect(namespaceSelect.value).toBe("examples");
    expect(nameSelect.value).toBe("hello");
    expect(versionSelect.value).toBe("2.0.0");
    expect(versionSelect).not.toBeDisabled();
  });
});
