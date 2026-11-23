# Processes and child workflows

Use `run.workflow` for an existing child definition. Supply its namespace, name, version and input. Choose whether the parent should wait based on whether it consumes the child’s result.

For scripts and shell tasks, check that the requested interpreter and files exist on the worker. Treat user input as data rather than executable command text. Verify the return mode against what the next task consumes.

Lemline uses a name/value map for script arguments. The repository schema checker translates that map to the ordered list used by the published schema when checking the definition. The JVM reader checks the original file.

[Lemline documentation](../../../../lemline-docs/topics/dsl-run-overview.md)
