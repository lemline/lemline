Build an incident-handling workflow triggered by high-severity alerts.
After start, wait for either ack or escalation event.
If ack arrives, notify chat.
If escalation arrives, notify both pager and on-call service in parallel, first success wins.
Always publish an incident-processed event.

