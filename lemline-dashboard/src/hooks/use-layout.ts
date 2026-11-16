import { useCallback, useEffect, useRef, useState } from "react";
import { useViewportWidth } from "./use-viewport-width";

const CLOSE_ANIMATION_MS = 180;

/** Plays the slide-out of a side panel before calling `onClose`. */
export function useDrawerClose(onClose: () => void) {
  const [closing, setClosing] = useState(false);
  const timer = useRef<number | null>(null);

  useEffect(
    () => () => {
      if (timer.current != null) window.clearTimeout(timer.current);
    },
    [],
  );

  const close = useCallback(() => {
    if (timer.current != null) return;
    setClosing(true);
    timer.current = window.setTimeout(() => {
      timer.current = null;
      setClosing(false);
      onClose();
    }, CLOSE_ANIMATION_MS);
  }, [onClose]);

  return { closing, close };
}

export function useDrawerPadding() {
  const narrow = useViewportWidth() < 720;
  return {
    head: narrow ? "20px 16px" : "28px 32px 20px",
    body: narrow ? "20px 16px" : "24px 32px",
    foot: narrow ? "16px" : "20px 32px",
  };
}

export function usePagePadding(): string {
  return useViewportWidth() < 720 ? "20px 16px 32px" : "28px 40px 48px";
}
