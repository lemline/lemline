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
  - FirstTask:
      set:
        message: "Hello, World!"
      then: SecondTask
  - SecondTask:
      set:
        message: "This is the second task"
      then: FinalTask
  - FinalTask:
      set:
        message: "This is the final task"
      then: end
```

Each task specifies which task to execute next, creating a simple chain of execution.

## Ending Workflow Execution

To end the workflow execution, use the `end: true` property:

```yaml
  - CompleteTask:
      set:
        status: "COMPLETED"
        message: "Workflow completed successfully"
      then: end
```

When a task with `end: true` is executed, the workflow terminates, and its output becomes the workflow's final output.

## Conditional Jumping with `switch`

The `switch` task allows you to implement conditional jumping:

```yaml
  - EvaluateCondition:
      switch:
        - case1:
            then: HandleHotWeather
            when: ${ .temperature > 30 }
        - case2:
            then: HandleColdWeather
            when: ${ .temperature < 10 }
        - default:
            then: HandleMildWeather
  - HandleHotWeather:
      set:
        message: "It's hot outside!"
      then: PrepareResponse
  - HandleColdWeather:
      set:
        message: "It's cold outside!"
      then: PrepareResponse
  - HandleMildWeather:
      set:
        message: "The weather is mild."
      then: PrepareResponse
  - PrepareResponse:
      set:
        response: "Weather notification: .message"
      then: end
```

Based on the condition evaluation, the workflow jumps to one of the specified tasks.

## Using `then` to Jump from Branches

The `then` directive is used to specify where execution should continue after completing a complex branch task like `do` or `for`:

```yaml
  - ProcessData:
      do:
        - DoTask1:
            set:
              result1: "Step 1 complete"
        - DoTask2:
            set:
              result2: "Step 2 complete"
      then: FinalizeProcess
  - AlternativeProcess:
      set:
        message: "Alternative process"
      then: FinalizeProcess
  - FinalizeProcess:
      set:
        message: "Process finalized"
      then: end
```

In this example, after completing `DoTask1` and `DoTask2`, execution jumps to `FinalizeProcess` due to the `then` directive.

## Early Termination with `exit`

You can use the `exit` directive to terminate a branch early and continue execution elsewhere:

```yaml
  - ProcessItems:
      for:
        each: "item"
        in: ${ .items }
      do:
        - CheckItem:
            switch:
              - case1:
                  then: HandleCriticalItem
                  when: ${ .item.status == "CRITICAL" }
              - default:
                  then: ProcessNormalItem
        - ProcessNormalItem:
            set:
              processed: true
      then: FinalizeProcessing
  - HandleCriticalItem:
      set:
        status: "CRITICAL"
        message: "Critical item detected"
      then: end
  - FinalizeProcessing:
      set:
        status: "COMPLETED"
        message: "All items processed"
      then: end
```

In this example, if a critical item is found during iteration, the loop is terminated early with `exit` and execution jumps to `HandleCriticalItem`.

## Jumping Between Different Workflow Sections

For complex workflows, you often need to jump between different logical sections:

```yaml
  - StartProcess:
      set:
        orderType: ${ .orderType }
      then: RouteOrder
  - RouteOrder:
      switch:
        - case1:
            then: StandardOrderProcess
            when: ${ .orderType == "STANDARD" }
        - case2:
            then: ExpeditedOrderProcess
            when: ${ .orderType == "EXPEDITED" }
        - case3:
            then: ReturnProcess
            when: ${ .orderType == "RETURN" }
        - default:
            then: HandleUnknownOrderType
  - StandardOrderProcess:
      set:
        processingTime: "3-5 days"
      then: VerifyInventory
  - ExpeditedOrderProcess:
      set:
        processingTime: "1-2 days"
        rushFee: 15.00
      then: VerifyInventory
  - ReturnProcess:
      set:
        returnType: ${ .returnReason }
      then: HandleReturn
  - VerifyInventory:
      call: inventoryService
      with:
        items: ${ .items }
      then: CheckInventoryResult
  - CheckInventoryResult:
      switch:
        - case1:
            then: ProcessPayment
            when: ${ .available == true }
        - default:
            then: HandleOutOfStock
  - ProcessPayment:
      call: paymentService
      with:
        amount: ${ .total + (.rushFee // 0) }
      then: FinalizeOrder
  - FinalizeOrder:
      set:
        status: "COMPLETED"
        message: "Order processed successfully"
      then: end
  - HandleOutOfStock:
      set:
        status: "FAILED"
        reason: "Items out of stock"
      then: end
  - HandleReturn:
      set:
        status: "RETURN_INITIATED"
        returnId: ${ "RTN-" + (.orderId) }
      then: end
  - HandleUnknownOrderType:
      set:
        status: "FAILED"
        reason: '${ "Unknown order type: \(.orderType)" }'
      then: end
```

This example shows how a workflow can jump between different sections based on order type, with each section potentially leading to different end states.

## Dynamic Jumping with Expressions

You can implement dynamic jumping by combining expressions with switch conditions:

```yaml
  - DecideNextStep:
      set:
        nextTaskName: "${ if .orderValue > 1000 then 'HighValueProcess' else (.customerTier == 'PREMIUM' ? 'PremiumProcess'
          : 'StandardProcess') end }"
      then: RouteByName
  - RouteByName:
      switch:
        - case1:
            then: HighValueProcess
            when: ${ .nextTaskName == 'HighValueProcess' }
        - case2:
            then: PremiumProcess
            when: ${ .nextTaskName == 'PremiumProcess' }
        - case3:
            then: StandardProcess
            when: ${ .nextTaskName == 'StandardProcess' }
        - default:
            then: DefaultProcess
```

This approach lets you calculate the next task name dynamically based on complex criteria.

## Implementing State Machines

You can implement state machine patterns by tracking current state and transitioning between states:

```yaml
  - InitializeStateMachine:
      set:
        currentState: "STARTED"
        availableTransitions:
          STARTED:
            - "VALIDATING"
            - "REJECTED"
          VALIDATING:
            - "PROCESSING"
            - "REJECTED"
          PROCESSING:
            - "COMPLETED"
            - "FAILED"
          FAILED:
            - "RETRY"
            - "REJECTED"
          RETRY:
            - "PROCESSING"
            - "REJECTED"
        event: ${ .event }
      then: ProcessStateTransition
  - ProcessStateTransition:
      set:
        targetState: ${ .event.targetState }
        isValidTransition: ${ .availableTransitions[.currentState] | contains([.targetState]) }
        previousState: ${ .currentState }
      then: ValidateTransition
  - ValidateTransition:
      switch:
        - case1:
            then: RejectTransition
            when: ${ .isValidTransition == false }
        - case2:
            then: EnterValidatingState
            when: ${ .targetState == 'VALIDATING' }
        - case3:
            then: EnterProcessingState
            when: ${ .targetState == 'PROCESSING' }
        - case4:
            then: EnterCompletedState
            when: ${ .targetState == 'COMPLETED' }
        - case5:
            then: EnterFailedState
            when: ${ .targetState == 'FAILED' }
        - case6:
            then: EnterRetryState
            when: ${ .targetState == 'RETRY' }
        - case7:
            then: EnterRejectedState
            when: ${ .targetState == 'REJECTED' }
        - default:
            then: RejectTransition
  - EnterValidatingState:
      set:
        currentState: "VALIDATING"
        stateData:
          enteredAt: ${ $context.currentTime }
          transition: ${ .previousState + ' -> VALIDATING' }
      then: PerformValidation
  - PerformValidation:
      call: validationService
      with:
        orderId: ${ .orderId }
      then: CheckValidationResult
  - CheckValidationResult:
      switch:
        - case1:
            then: TransitionToProcessing
            when: ${ .isValid == true }
        - default:
            then: TransitionToRejected
  - TransitionToProcessing:
      set:
        event:
          targetState: "PROCESSING"
      then: ProcessStateTransition
  - TransitionToRejected:
      set:
        event:
          targetState: "REJECTED"
          reason: ${ .validationErrors }
      then: ProcessStateTransition
  - EnterCompletedState:
      set:
        currentState: "COMPLETED"
        stateData:
          completedAt: ${ $context.currentTime }
          transition: ${ .previousState + ' -> COMPLETED' }
      then: end
  - EnterRejectedState:
      set:
        currentState: "REJECTED"
        stateData:
          rejectedAt: ${ $context.currentTime }
          transition: ${ .previousState + ' -> REJECTED' }
          reason: ${ .event.reason }
      then: end
  - RejectTransition:
      set:
        error: "Invalid state transition from .currentState to .targetState"
      then: end
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
  - ValidateInput:
      switch:
        - case1:
            then: RejectMissingInput
            when: ${ .input == null }
        - case2:
            then: RejectInvalidAmount
            when: ${ .input.amount <= 0 }
        - default:
            then: ProcessValidInput
  - RejectMissingInput:
      set:
        status: "REJECTED"
        reason: "Missing input"
      then: end
  - RejectInvalidAmount:
      set:
        status: "REJECTED"
        reason: "Invalid amount"
      then: end
  - ProcessValidInput:
      set: ${ . }
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
  - InitializeLoop:
      set:
        items: ${ .items }
        processedItems: []
        errorCount: 0
      then: ProcessItems
  - ProcessItems:
      for:
        each: "item"
        in: ${ .items }
      do:
        - ProcessItem:
            try:
              - AttemptProcessing:
                  call: processItem
                  with:
                    itemId: ${ .item.id }
            catch:
              as: failure
              then: HandleItemError
              retry:
                limit:
                  attempt:
                    count: 3
        - SaveProcessedItem:
            set:
              processedItem:
                id: ${ .item.id }
                result: "SUCCESS"
        - HandleItemError:
            set:
              processedItem:
                id: ${ .item.id }
                result: "ERROR"
                error: ${ $context.error }
        - UpdateCounts:
            set:
              errorCount: ${ if .errorCount + (.processedItem.result == "ERROR" then 1 else 0) end }
        - CheckErrorThreshold:
            switch:
              - case1:
                  then: AbortProcessing
                  when: ${ .errorCount >= 3 }
              - default:
                  then: ContinueProcessing
        - ContinueProcessing:
            set:
              processedItems: ${ .processedItems + [.processedItem] }
      then: CompleteProcessing
  - AbortProcessing:
      set:
        status: "ABORTED"
        reason: "Too many errors encountered"
        processedItems: ${ .processedItems }
      then: end
  - CompleteProcessing:
      set:
        status: "COMPLETED"
        processedItems: ${ .processedItems }
        errorCount: ${ .errorCount }
      then: end
```

This pattern exits a loop early if too many errors are encountered.

## Next Steps

- Learn about [implementing conditional branches](lemline-howto-conditional.md)
- Explore [executing tasks in parallel](lemline-howto-parallel.md)
- Understand [running loops in workflows](lemline-howto-loops.md)