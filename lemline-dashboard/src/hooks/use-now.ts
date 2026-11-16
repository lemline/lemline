import { useEffect, useState } from "react";

/** Current time, refreshed every `intervalMs` while `live` is true. */
export function useNow(intervalMs: number, live = true): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!live) return;
    const timer = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(timer);
  }, [intervalMs, live]);
  return now;
}
