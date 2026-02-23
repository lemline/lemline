import { act, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { useViewportWidth } from "../src/hooks/use-viewport-width";

function WidthProbe() {
  const width = useViewportWidth();
  return <span data-testid="width">{width}</span>;
}

function setViewportWidth(width: number) {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: width,
  });
}

describe("useViewportWidth", () => {
  it("reads initial width and updates when resize events fire", () => {
    setViewportWidth(1200);
    render(<WidthProbe />);
    expect(screen.getByTestId("width")).toHaveTextContent("1200");

    act(() => {
      setViewportWidth(840);
      window.dispatchEvent(new Event("resize"));
    });

    expect(screen.getByTestId("width")).toHaveTextContent("840");
  });
});
