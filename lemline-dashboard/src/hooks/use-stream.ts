import { useEffect } from "react";

export function useStream<T>(
  enabled: boolean,
  streamFactory: (signal: AbortSignal) => AsyncIterable<T>,
  onMessage: (message: T) => void,
  dependencies: unknown[] = [],
) {
  useEffect(() => {
    if (!enabled) return;

    const controller = new AbortController();
    let mounted = true;

    const run = async () => {
      try {
        for await (const message of streamFactory(controller.signal)) {
          if (!mounted) break;
          onMessage(message);
        }
      } catch {
        // Stream errors are handled by the caller's reconnect strategy.
      }
    };

    void run();

    return () => {
      mounted = false;
      controller.abort();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, ...dependencies]);
}
