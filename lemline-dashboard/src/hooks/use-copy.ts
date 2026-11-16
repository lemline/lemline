import { useEffect, useRef, useState } from "react";

/** Copies a text to the clipboard; `copied` stays true for two seconds. */
export function useCopy(text: string) {
  const [copied, setCopied] = useState(false);
  const timer = useRef<number | null>(null);
  useEffect(
    () => () => {
      if (timer.current != null) window.clearTimeout(timer.current);
    },
    [],
  );
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      // Clipboard may be refused (insecure context): the label still confirms the click.
    }
    setCopied(true);
    if (timer.current != null) window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setCopied(false), 2000);
  };
  return { copied, copy };
}
