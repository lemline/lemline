- check row unicity on databases, based on position (for example, what happen if a listen is retried following a failed
  one - bad smell: findByWorkflowIdAndPosition method)
- optimize handleListenForEachCompleted (reduce the # of db calls)
- check behavior when a failure occurs in listen.forEach
- correctly implement FullOrchestrator for the listen and listen + foreach feature
- shorten serialisation keys for InstantMessage
