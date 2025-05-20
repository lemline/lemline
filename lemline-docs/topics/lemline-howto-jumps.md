---
title: How to jump between tasks (then, exit, end)
---

# How to jump between tasks (then, exit, end)

This guide explains how to control the flow of execution in Lemline workflows by jumping between tasks using features like `next`, `then`, `exit`, and `end` directives. 

## Understanding Flow Control in Lemline

Lemline provides several mechanisms for controlling the flow of execution in workflows:

| Mechanism | Description | Use Case |
|-----------|-------------|----------|
| `next` | Specifies the next task to execute after the current task | Basic sequential flow |
| `then` | Jump to a specific task after completing a branch | Complex branching |
| `end: true` | End the workflow execution | Terminating execution |
| `exit` | Exit a branch and continue at a specified point | Escaping from a branch |

These mechanisms allow you to create complex execution paths through your workflow.

## Basic Task Transitions with `next`

The most common way to control workflow flow is using the `next` property:

```yaml
- name: FirstTask
  type: set
  data:
    message: "Hello, World!"
  next: SecondTask

- name: SecondTask
  type: set
  data:
    message: "This is the second task"
  next: FinalTask

- name: FinalTask
  type: set
  data:
    message: "This is the final task"
  end: true
```

Each task specifies which task to execute next, creating a simple chain of execution.

## Ending Workflow Execution

To end the workflow execution, use the `end: true` property:

```yaml
- name: CompleteTask
  type: set
  data:
    status: "COMPLETED"
    message: "Workflow completed successfully"
  end: true
```

When a task with `end: true` is executed, the workflow terminates, and its output becomes the workflow's final output.

## Conditional Jumping with `switch`

The `switch` task allows you to implement conditional jumping:

```yaml
- name: EvaluateCondition
  type: switch
  conditions:
    - condition: ".temperature > 30"
      next: HandleHotWeather
    - condition: ".temperature < 10"
      next: HandleColdWeather
    - condition: true
      next: HandleMildWeather

- name: HandleHotWeather
  type: set
  data:
    message: "It's hot outside!"
  next: PrepareResponse

- name: HandleColdWeather
  type: set
  data:
    message: "It's cold outside!"
  next: PrepareResponse

- name: HandleMildWeather
  type: set
  data:
    message: "The weather is mild."
  next: PrepareResponse

- name: PrepareResponse
  type: set
  data:
    response: "Weather notification: .message"
  end: true
```

Based on the condition evaluation, the workflow jumps to one of the specified tasks.

## Using `then` to Jump from Branches

The `then` directive is used to specify where execution should continue after completing a complex branch task like `do` or `for`:

```yaml
- name: ProcessData
  type: do
    - name: DoTask1
      type: set
      data:
        result1: "Step 1 complete"
    - name: DoTask2
      type: set
      data:
        result2: "Step 2 complete"
  then: FinalizeProcess  # Jump here after completing the do tasks

- name: AlternativeProcess
  type: set
  data:
    message: "Alternative process"
  next: FinalizeProcess

- name: FinalizeProcess
  type: set
  data:
    message: "Process finalized"
  end: true
```

In this example, after completing `DoTask1` and `DoTask2`, execution jumps to `FinalizeProcess` due to the `then` directive.

## Early Termination with `exit`

You can use the `exit` directive to terminate a branch early and continue execution elsewhere:

```yaml
- name: ProcessItems
  type: for
  iterator:
    collect: ".items"
    as: "item"
  do:
    - name: CheckItem
      type: switch
      conditions:
        - condition: ".item.status == 'CRITICAL'"
          exit: HandleCriticalItem  # Exit the loop on critical items
        - condition: true
          next: ProcessNormalItem
    - name: ProcessNormalItem
      type: set
      data:
        processed: true
  next: FinalizeProcessing

- name: HandleCriticalItem
  type: set
  data:
    status: "CRITICAL"
    message: "Critical item detected"
  end: true

- name: FinalizeProcessing
  type: set
  data:
    status: "COMPLETED"
    message: "All items processed"
  end: true
```

In this example, if a critical item is found during iteration, the loop is terminated early with `exit` and execution jumps to `HandleCriticalItem`.

## Jumping Between Different Workflow Sections

For complex workflows, you often need to jump between different logical sections:

```yaml
- name: StartProcess
  type: set
  data:
    orderType: ".orderType"
  next: RouteOrder

- name: RouteOrder
  type: switch
  conditions:
    - condition: ".orderType == 'STANDARD'"
      next: StandardOrderProcess
    - condition: ".orderType == 'EXPEDITED'"
      next: ExpeditedOrderProcess
    - condition: ".orderType == 'RETURN'"
      next: ReturnProcess
    - condition: true
      next: HandleUnknownOrderType

# Standard Order Section
- name: StandardOrderProcess
  type: set
  data:
    processingTime: "3-5 days"
  next: VerifyInventory

# Expedited Order Section
- name: ExpeditedOrderProcess
  type: set
  data:
    processingTime: "1-2 days"
    rushFee: 15.00
  next: VerifyInventory

# Return Section
- name: ReturnProcess
  type: set
  data:
    returnType: ".returnReason"
  next: HandleReturn

# Common Inventory Section
- name: VerifyInventory
  type: call
  function: inventoryService
  data:
    items: ".items"
  next: CheckInventoryResult

- name: CheckInventoryResult
  type: switch
  conditions:
    - condition: ".available == true"
      next: ProcessPayment
    - condition: true
      next: HandleOutOfStock

# Payment Section
- name: ProcessPayment
  type: call
  function: paymentService
  data:
    amount: ".total + (.rushFee || 0)"
  next: FinalizeOrder

# Various End States
- name: FinalizeOrder
  type: set
  data:
    status: "COMPLETED"
    message: "Order processed successfully"
  end: true

- name: HandleOutOfStock
  type: set
  data:
    status: "FAILED"
    reason: "Items out of stock"
  end: true

- name: HandleReturn
  type: set
  data:
    status: "RETURN_INITIATED"
    returnId: "RTN-" + (.orderId)
  end: true

- name: HandleUnknownOrderType
  type: set
  data:
    status: "FAILED"
    reason: "Unknown order type: .orderType"
  end: true
```

This example shows how a workflow can jump between different sections based on order type, with each section potentially leading to different end states.

## Dynamic Jumping with Expressions

You can implement dynamic jumping by combining expressions with switch conditions:

```yaml
- name: DecideNextStep
  type: set
  data:
    nextTaskName: ".orderValue > 1000 ? 'HighValueProcess' : (.customerTier == 'PREMIUM' ? 'PremiumProcess' : 'StandardProcess')"
  next: RouteByName

- name: RouteByName
  type: switch
  conditions:
    - condition: ".nextTaskName == 'HighValueProcess'"
      next: HighValueProcess
    - condition: ".nextTaskName == 'PremiumProcess'"
      next: PremiumProcess
    - condition: ".nextTaskName == 'StandardProcess'"
      next: StandardProcess
    - condition: true
      next: DefaultProcess
```

This approach lets you calculate the next task name dynamically based on complex criteria.

## Implementing State Machines

You can implement state machine patterns by tracking current state and transitioning between states:

```yaml
- name: InitializeStateMachine
  type: set
  data:
    currentState: "STARTED"
    availableTransitions:
      STARTED: ["VALIDATING", "REJECTED"]
      VALIDATING: ["PROCESSING", "REJECTED"]
      PROCESSING: ["COMPLETED", "FAILED"]
      FAILED: ["RETRY", "REJECTED"]
      RETRY: ["PROCESSING", "REJECTED"]
    event: ".event"
  next: ProcessStateTransition

- name: ProcessStateTransition
  type: set
  data:
    targetState: ".event.targetState"
    isValidTransition: ".availableTransitions[.currentState] | contains([.targetState])"
    previousState: ".currentState"
  next: ValidateTransition

- name: ValidateTransition
  type: switch
  conditions:
    - condition: ".isValidTransition == false"
      next: RejectTransition
    - condition: ".targetState == 'VALIDATING'"
      next: EnterValidatingState
    - condition: ".targetState == 'PROCESSING'"
      next: EnterProcessingState
    - condition: ".targetState == 'COMPLETED'"
      next: EnterCompletedState
    - condition: ".targetState == 'FAILED'"
      next: EnterFailedState
    - condition: ".targetState == 'RETRY'"
      next: EnterRetryState
    - condition: ".targetState == 'REJECTED'"
      next: EnterRejectedState
    - condition: true
      next: RejectTransition

# State handler tasks
- name: EnterValidatingState
  type: set
  data:
    currentState: "VALIDATING"
    stateData:
      enteredAt: "$WORKFLOW.currentTime"
      transition: ".previousState + ' -> VALIDATING'"
  next: PerformValidation

- name: PerformValidation
  type: call
  function: validationService
  data:
    orderId: ".orderId"
  next: CheckValidationResult

- name: CheckValidationResult
  type: switch
  conditions:
    - condition: ".isValid == true"
      next: TransitionToProcessing
    - condition: true
      next: TransitionToRejected

- name: TransitionToProcessing
  type: set
  data:
    event:
      targetState: "PROCESSING"
  next: ProcessStateTransition

- name: TransitionToRejected
  type: set
  data:
    event:
      targetState: "REJECTED"
      reason: ".validationErrors"
  next: ProcessStateTransition

# Similar patterns for other states...

- name: EnterCompletedState
  type: set
  data:
    currentState: "COMPLETED"
    stateData:
      completedAt: "$WORKFLOW.currentTime"
      transition: ".previousState + ' -> COMPLETED'"
  end: true

- name: EnterRejectedState
  type: set
  data:
    currentState: "REJECTED"
    stateData:
      rejectedAt: "$WORKFLOW.currentTime"
      transition: ".previousState + ' -> REJECTED'"
      reason: ".event.reason"
  end: true

- name: RejectTransition
  type: set
  data:
    error: "Invalid state transition from .currentState to .targetState"
  end: true
```

This complex example implements a state machine with defined transitions between states.

## Best Practices for Task Jumping

1. **Maintain Clear Flow**: Design your workflow with a clear and easy-to-follow execution path
2. **Document Jump Points**: Comment complex jumps to explain the flow logic
3. **Avoid Excessive Jumping**: Too many jumps make workflows hard to understand
4. **Use Consistent Patterns**: Follow consistent patterns for similar types of jumps
5. **Consider Fault Tolerance**: Ensure error handling works correctly with your jump patterns
6. **Test All Paths**: Thoroughly test all possible execution paths
7. **Use Descriptive Task Names**: Name tasks descriptively to clarify the flow
8. **Group Related Tasks**: Keep related tasks close together in the workflow definition

## Common Jumping Patterns

### Early Exit Pattern

```yaml
- name: ValidateInput
  type: switch
  conditions:
    - condition: ".input == null"
      next: RejectMissingInput
    - condition: ".input.amount <= 0"
      next: RejectInvalidAmount
    - condition: true
      next: ProcessValidInput

- name: RejectMissingInput
  type: set
  data:
    status: "REJECTED"
    reason: "Missing input"
  end: true

- name: RejectInvalidAmount
  type: set
  data:
    status: "REJECTED"
    reason: "Invalid amount"
  end: true

- name: ProcessValidInput
  # Continue with normal processing
```

This pattern checks preconditions and exits early if they aren't met.

### Callback Pattern

```yaml
- name: InitiateProcess
  type: set
  data:
    callbackTask: ".callbackTask || 'DefaultCallback'"
  next: PerformProcess

- name: PerformProcess
  type: call
  function: someService
  data:
    processId: ".processId"
  next: DetermineCallback

- name: DetermineCallback
  type: switch
  conditions:
    - condition: ".callbackTask == 'CallbackA'"
      next: CallbackA
    - condition: ".callbackTask == 'CallbackB'"
      next: CallbackB
    - condition: true
      next: DefaultCallback

- name: CallbackA
  # Handle callback A
  end: true

- name: CallbackB
  # Handle callback B
  end: true

- name: DefaultCallback
  # Handle default callback
  end: true
```

This pattern allows different callback tasks to be specified dynamically.

### Conditional Exit from Loops

```yaml
- name: InitializeLoop
  type: set
  data:
    items: ".items"
    processedItems: []
    errorCount: 0
  next: ProcessItems

- name: ProcessItems
  type: for
  iterator:
    collect: ".items"
    as: "item"
  do:
    - name: ProcessItem
      type: try
      retry:
        maxAttempts: 3
      catch:
        - error: "*"
          next: HandleItemError
      do:
        - name: AttemptProcessing
          type: call
          function: processItem
          data:
            itemId: ".item.id"
      
    - name: SaveProcessedItem
      type: set
      data:
        processedItem:
          id: ".item.id"
          result: "SUCCESS"
      
    - name: HandleItemError
      type: set
      data:
        processedItem:
          id: ".item.id"
          result: "ERROR"
          error: "$WORKFLOW.error"
      
    - name: UpdateCounts
      type: set
      data:
        errorCount: ".errorCount + (.processedItem.result == 'ERROR' ? 1 : 0)"
      
    - name: CheckErrorThreshold
      type: switch
      conditions:
        - condition: ".errorCount >= 3"
          exit: AbortProcessing
        - condition: true
          next: ContinueProcessing
      
    - name: ContinueProcessing
      type: set
      data:
        processedItems: ".processedItems + [.processedItem]"
  next: CompleteProcessing

- name: AbortProcessing
  type: set
  data:
    status: "ABORTED"
    reason: "Too many errors encountered"
    processedItems: ".processedItems"
  end: true

- name: CompleteProcessing
  type: set
  data:
    status: "COMPLETED"
    processedItems: ".processedItems"
    errorCount: ".errorCount"
  end: true
```

This pattern exits a loop early if too many errors are encountered.

## Next Steps

- Learn about [implementing conditional branches](lemline-howto-conditional.md)
- Explore [executing tasks in parallel](lemline-howto-parallel.md)
- Understand [running loops in workflows](lemline-howto-loops.md)