import { Card } from "../ui/card";
import { asNumber } from "../../lib/utils";

interface StatsCardsProps {
  started: bigint | number | string;
  completed: bigint | number | string;
  faulted: bigint | number | string;
  running: bigint | number | string;
}

export function StatsCards({ started, completed, faulted, running }: StatsCardsProps) {
  const cards = [
    { label: "Started", value: asNumber(started) },
    { label: "Completed", value: asNumber(completed) },
    { label: "Faulted", value: asNumber(faulted) },
    { label: "Running", value: asNumber(running) },
  ];

  return (
    <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      {cards.map((card) => (
        <Card key={card.label}>
          <p className="text-xs uppercase tracking-wide text-slate-500 dark:text-slate-400">{card.label}</p>
          <p className="mt-2 text-3xl font-semibold tabular-nums">{card.value.toLocaleString()}</p>
        </Card>
      ))}
    </div>
  );
}
