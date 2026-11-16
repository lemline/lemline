import { useCopy } from "../../hooks/use-copy";
import { cn } from "../../lib/utils";
import { CopyIcon } from "./icons";

interface CodeBlockProps {
  text: string;
  /** "small": drawer blocks with a compact Copy button; "large": YAML source. */
  size?: "small" | "large";
  className?: string;
  ariaLabel?: string;
}

export function CodeBlock({ text, size = "small", className, ariaLabel }: CodeBlockProps) {
  const { copied, copy } = useCopy(text);
  const large = size === "large";
  return (
    <div
      className={cn(
        "relative overflow-hidden",
        large ? "rounded-[14px] border border-lm-line bg-lm-panel shadow-lm-card" : "rounded-[10px] border border-lm-line bg-lm-subtle",
        className,
      )}
      aria-label={ariaLabel}
    >
      <button
        type="button"
        onClick={() => void copy()}
        className={cn(
          "absolute z-[1] flex cursor-pointer items-center gap-1.5 rounded-lg border bg-lm-panel font-sans",
          large
            ? "right-3 top-3 min-h-10 border-lm-line-strong px-3 text-sm font-medium text-lm-text"
            : "right-2.5 top-2.5 min-h-8 border-lm-line px-3 text-[13px] font-semibold text-lm-text2",
        )}
      >
        {large && <CopyIcon />}
        {copied ? "Copied" : "Copy"}
      </button>
      <pre
        className={cn(
          "m-0 overflow-x-auto whitespace-pre font-mono text-lm-text",
          large ? "py-5 pl-6 pr-[120px] text-sm leading-[1.7]" : "py-[18px] pl-[18px] pr-24 text-[13px] leading-[1.6]",
        )}
      >
        {text}
      </pre>
    </div>
  );
}
