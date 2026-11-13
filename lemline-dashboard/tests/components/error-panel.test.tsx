import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ErrorPanel } from "../../src/components/workflow/error-panel";

describe("ErrorPanel", () => {
  it("shows the error detail separately from its title", () => {
    render(
      <ErrorPanel
        title="HTTP 403 Forbidden"
        detail="Response content type: text/html\nResponse body:\n<!DOCTYPE html>"
        type="https://serverlessworkflow.io/spec/1.0.0/errors/runtime"
        status={403}
        payloadJson="{}"
      />,
    );

    expect(screen.getByText("HTTP 403 Forbidden")).toBeInTheDocument();
    expect(screen.getByText("Response content type: text/html", { exact: false })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Show raw event" })).toBeInTheDocument();
  });
});
