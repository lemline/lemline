import { useQueryClient, type QueryKey } from "@tanstack/react-query";
import { useEffect } from "react";

const MAX_RETRIES = 5;
const DEBOUNCE_MS = 300;

interface LiveOptions<T> {
  /** Starts after the first snapshot is loaded. */
  enabled: boolean;
  /** Query refreshed when the stream reports a change. */
  queryKey: QueryKey;
  /** Opens the change stream from `cursor`. */
  watch: (cursor: string | undefined, signal: AbortSignal) => AsyncIterable<T>;
  cursorOf: (message: T) => string | undefined;
  initialCursor?: string;
  /** Stable identity of the stream (filters): a change reopens it. */
  identity: string;
  /** Called when the stream is lost for good, so the caller can fall back to polling. */
  onLost: (identity: string) => void;
}

/**
 * Keeps a query live: the gateway streams each change, and the query is refreshed right after.
 */
export function useLiveInvalidation<T>({ enabled, queryKey, watch, cursorOf, initialCursor, identity, onLost }: LiveOptions<T>): void {
  const queryClient = useQueryClient();

  useEffect(() => {
    if (!enabled) return;
    const controller = new AbortController();
    let cursor = initialCursor;
    let timer: number | null = null;
    let retries = 0;

    const refresh = () => {
      if (timer != null) return;
      timer = window.setTimeout(() => {
        timer = null;
        void queryClient.invalidateQueries({ queryKey });
      }, DEBOUNCE_MS);
    };

    const run = async () => {
      while (!controller.signal.aborted) {
        try {
          for await (const message of watch(cursor, controller.signal)) {
            retries = 0;
            cursor = cursorOf(message) || cursor;
            refresh();
          }
          if (controller.signal.aborted) return;
          throw new Error("stream ended");
        } catch {
          if (controller.signal.aborted) return;
          retries += 1;
          if (retries > MAX_RETRIES) {
            onLost(identity);
            return;
          }
          await new Promise((resolve) => window.setTimeout(resolve, Math.min(1000 * 2 ** (retries - 1), 15_000)));
        }
      }
    };
    void run();

    return () => {
      controller.abort();
      if (timer != null) window.clearTimeout(timer);
    };
    // The stream is reopened only when the filters change, not on each refreshed snapshot.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, identity]);
}
