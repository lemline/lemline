import { useEffect, useState } from "react";

const DEFAULT_WIDTH = 1440;

function readViewportWidth(): number {
  if (typeof window === "undefined") return DEFAULT_WIDTH;
  return window.innerWidth;
}

export function useViewportWidth(): number {
  const [width, setWidth] = useState<number>(() => readViewportWidth());

  useEffect(() => {
    if (typeof window === "undefined") return;

    const onResize = () => {
      setWidth(window.innerWidth);
    };

    window.addEventListener("resize", onResize);
    return () => {
      window.removeEventListener("resize", onResize);
    };
  }, []);

  return width;
}
