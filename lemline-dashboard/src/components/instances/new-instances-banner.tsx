import { Button } from "../ui/button";

interface NewInstancesBannerProps {
  count: number;
  onApply: () => void;
}

export function NewInstancesBanner({ count, onApply }: NewInstancesBannerProps) {
  if (count <= 0) return null;

  return (
    <div className="sticky top-0 z-10 mb-3 rounded-md border border-cyan-300 bg-cyan-50 px-3 py-2 dark:border-cyan-900 dark:bg-cyan-950/30">
      <div className="flex items-center justify-between gap-3">
        <p className="text-sm text-cyan-800 dark:text-cyan-200">{count} new instances available.</p>
        <Button variant="outline" onClick={onApply}>
          Show updates
        </Button>
      </div>
    </div>
  );
}
