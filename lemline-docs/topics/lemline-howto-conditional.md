---
title: How to define conditional branches (switch)
---

# How to define conditional branches (switch)

This guide shows you how to implement conditional logic in your Lemline workflows using the `switch` task. You'll learn how to create dynamic branching based on workflow data, evaluate conditions using JQ expressions, and implement complex decision trees.

## Understanding the Switch Task

The `switch` task lets you create conditional branches in your workflow based on runtime data. It evaluates a list of conditions in order and directs the workflow to the first matching branch.

## Basic Switch Structure

Here's the basic structure of a `switch` task:

```yaml
  - DecideAction:
      switch:
        - case1:
            then: HandleHotWeather
            when: ${ .temperature > 30 }
        - case2:
            then: HandleColdWeather
            when: ${ .temperature < 10 }
        - default:
            then: HandleMildWeather
```

Each condition is a JQ expression that evaluates to a boolean. The first condition that evaluates to `true` determines the next task to execute.

## Simple Binary Decision

Let's start with a simple yes/no decision pattern:

```yaml
  - CheckInventory:
      call: inventoryService
      with:
        productId: ${ .productId }
        quantity: ${ .quantity }
      then: EvaluateInventory
  - EvaluateInventory:
      switch:
        - case1:
            then: ProcessOrder
            when: ${ .available == true }
        - default:
            then: HandleOutOfStock
```

This example checks inventory availability and branches to different tasks based on the result.

## Multiple Conditions

You can define multiple conditions for more complex decision trees:

```yaml
  - CategorizeLead:
      switch:
        - case1:
            then: ProcessHotLead
            when: ${ .score >= 80 }
        - case2:
            then: ProcessWarmLead
            when: ${ .score >= 50 and .score < 80 }
        - case3:
            then: ProcessCoolLead
            when: ${ .score >= 20 and .score < 50 }
        - default:
            then: ProcessColdLead
```

This example categorizes leads based on score ranges.

## Using Complex Expressions

JQ expressions in conditions can be complex logical statements:

```yaml
  - RouteCustomerRequest:
      switch:
        - case1:
            then: EscalateToSupervisor
            when: ${ .type == "refund" and .amount > 1000 }
        - case2:
            then: ApplyReturnPolicy
            when: ${ .type == "refund" and .daysSincePurchase > 30 }
        - case3:
            then: ProcessStandardRefund
            when: ${ .type == "refund" }
        - case4:
            then: ProcessDefectiveExchange
            when: ${ .type == "exchange" and .reason == "defective" }
        - case5:
            then: ProcessStandardExchange
            when: ${ .type == "exchange" }
        - default:
            then: HandleGenericRequest
```

This example routes customer requests based on multiple criteria.

## Default Fallback

Always include a default condition as the last one in your switch task to ensure the workflow always has a path forward:

```yaml
  - CheckOrderStatus:
      switch:
        - case1:
            then: ProcessApprovedOrder
            when: ${ .status == "approved" }
        - case2:
            then: WaitForApproval
            when: ${ .status == "pending" }
        - case3:
            then: HandleRejectedOrder
            when: ${ .status == "rejected" }
        - default:
            then: HandleUnknownStatus
```

The last condition using `true` ensures there's always a matching path, even if none of the explicit conditions match.

## Working with Arrays and Objects

JQ expressions can work with complex data structures:

```yaml
  - DetermineShippingMethod:
      switch:
        - case1:
            then: ArrangeFreightShipping
            when: ${ .items | map(.weight) | add > 20 }
        - case2:
            then: ArrangeExpressShipping
            when: ${ .shipping.express == true }
        - case3:
            then: ArrangePriorityShipping
            when: ${ .customer.tier == "premium" }
        - default:
            then: ArrangeStandardShipping
```

This example determines shipping method based on order weight, shipping preferences, and customer tier.

## Combining Multiple Variables

You can reference multiple variables in a single condition:

```yaml
  - ApplyDiscount:
      switch:
        - case1:
            then: ApplyPremiumDiscount
            when: ${ .customer.loyalty_years >= 5 and .order.total > 100 }
        - case2:
            then: ApplyStandardDiscount
            when: ${ .customer.loyalty_years >= 2 // .order.total > 200 }
        - case3:
            then: ApplySaleDiscount
            when: ${ .order.items | map(select(.category == "sale")) | length > 0 }
        - default:
            then: NoDiscount
```

This example determines discounts based on customer loyalty, order total, and item categories.

## Nested Decision Making

For complex decision making, you can use multiple switch tasks in sequence:

```yaml
  - EvaluateRiskLevel:
      switch:
        - case1:
            then: HighValueCheck
            when: ${ .transaction.amount > 10000 }
        - default:
            then: StandardCheck
  - HighValueCheck:
      switch:
        - case1:
            then: ManualReview
            when: ${ .customer.history.flags | length > 0 }
        - case2:
            then: AdditionalVerification
            when: ${ .customer.age < 90 }
        - default:
            then: AutoApprove
```

This example implements a two-level decision tree for risk evaluation.

## Using Data Transformations before Switching

You can use a `set` task to prepare data for a switch decision:

```yaml
  - PrepareDecisionData:
      set:
        isNewCustomer: ${ .customer.joinDate > now() - duration("P30D") }
        isHighValue: ${ .order.total > 1000 }
        hasRiskyItems: ${ .order.items | map(select(.category == "restricted")) | length > 0 }
      then: RouteOrder
  - RouteOrder:
      switch:
        - case1:
            then: ComplianceReview
            when: ${ .hasRiskyItems == true }
        - case2:
            then: VerificationProcess
            when: ${ .isNewCustomer == true and .isHighValue == true }
        - case3:
            then: PriorityProcessing
            when: ${ .isHighValue == true }
        - default:
            then: StandardProcessing
```

This pattern improves readability by computing derived values before the switch.

## Error Handling in Switch Tasks

If an expression in a condition results in an error, Lemline will treat it as `false`:

```yaml
  - ProcessPayment:
      switch:
        - case1:
            then: CompleteOrder
            when: ${ "try \(.paymentResult.status) == 'success' catch false" }
        - case2:
            then: WaitForPayment
            when: ${ "try \(.paymentResult.status) == 'pending' catch false" }
        - default:
            then: HandlePaymentFailure
```

This pattern handles cases where properties might be missing.

## Combining Switch with Try/Catch

For robust conditional handling, combine switch with try/catch:

```yaml
  - ProcessOrder:
      try:
        - AttemptPayment:
            call: paymentService
            with:
              amount: ${ .total }
              cardToken: ${ .paymentMethod.token }
            then: EvaluatePayment
        - EvaluatePayment:
            switch:
              - case1:
                  then: CompleteOrder
                  when: ${ .status == "success" }
              - case2:
                  then: FlagForReview
                  when: ${ .status == "review" }
              - default:
                  then: HandleFailure
      catch:
        as: failure
        do:
          - routeError:
              switch:
                - case1:
                    then: HandlePaymentDeclined
                    when: ${ $failure.type == "PAYMENT_DECLINED" }
                - case2:
                    then: HandleGenericError
        retry:
          limit:
            attempt:
              count: 3
```

This pattern provides comprehensive error handling around conditional logic.

## Real-World Example: Order Processing

Here's a complete example of an order processing workflow with multiple decision points:

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: order-processing
  version: 1.0.0
  title: Order Processing Workflow
do:
  - ValidateOrder:
      set:
        isValid: ${ .items | length > 0 }
        hasFulfillableItems: true
      then: CheckOrderValidity
  - CheckOrderValidity:
      switch:
        - case1:
            then: RejectOrder
            when: ${ .isValid == false }
        - default:
            then: CheckInventory
  - CheckInventory:
      call: http
      with:
        method: get
        endpoint: https://inventory-api.example.com/check
        query:
          items: '${ .items | map({id: .productId, quantity: .quantity}) }'
      then: EvaluateInventory
  - EvaluateInventory:
      switch:
        - case1:
            then: CalculateShipping
            when: ${ .allAvailable == true }
        - case2:
            then: AdjustForPartialInventory
            when: ${ .partiallyAvailable == true and .order.allowPartial == true }
        - default:
            then: HandleOutOfStock
  - AdjustForPartialInventory:
      set:
        items: ${ .items | map(select(.productId as $id | .availableItems | map(.id) | contains([$id]))) }
        message: "Some items were unavailable and have been removed from your order."
      then: CalculateShipping
  - CalculateShipping:
      call: http
      with:
        method: post
        endpoint: https://shipping-api.example.com/calculate
        body:
          destination: ${ .shippingAddress }
          items: ${ .items }
      then: DetermineShippingMethod
  - DetermineShippingMethod:
      switch:
        - case1:
            then: ApplyExpressShipping
            when: ${ .expressSelected == true and .expressAvailable == true }
        - case2:
            then: ApplyFreightShipping
            when: ${ .total.weight > 20 }
        - default:
            then: ApplyStandardShipping
  - ApplyExpressShipping:
      set:
        shippingMethod: "EXPRESS"
        shippingCost: ${ .rates.express }
        estimatedDelivery: ${ .deliveryEstimates.express }
      then: CalculateTotals
  - ApplyFreightShipping:
      set:
        shippingMethod: "FREIGHT"
        shippingCost: ${ .rates.freight }
        estimatedDelivery: ${ .deliveryEstimates.freight }
      then: CalculateTotals
  - ApplyStandardShipping:
      set:
        shippingMethod: "STANDARD"
        shippingCost: ${ .rates.standard }
        estimatedDelivery: ${ .deliveryEstimates.standard }
      then: CalculateTotals
  - CalculateTotals:
      set:
        subtotal: ${ .items | map(.price * .quantity) | add }
        tax: ${ .subtotal * 0.08 }
        total: ${ .subtotal + .tax + .shippingCost }
      then: ProcessPayment
  - ProcessPayment:
      call: http
      with:
        method: post
        endpoint: https://payment-api.example.com/process
        body:
          amount: ${ .total }
          method: ${ .paymentMethod }
          currency: "USD"
      then: EvaluatePayment
  - EvaluatePayment:
      switch:
        - case1:
            then: CompleteOrder
            when: ${ .status == "approved" }
        - case2:
            then: FlagForReview
            when: ${ .status == "review" }
        - case3:
            then: HandleDeclinedPayment
            when: ${ .status == "declined" }
        - default:
            then: HandlePaymentError
  - CompleteOrder:
      set:
        orderStatus: "CONFIRMED"
        confirmationNumber: ${ .transactionId }
        message: ${ "Your order has been confirmed and will ship via \(.shippingMethod)." }
      then: end
  - RejectOrder:
      set:
        orderStatus: "REJECTED"
        reason: "Invalid order"
        message: "Your order could not be processed because it contains no items."
      then: end
  - HandleOutOfStock:
      set:
        orderStatus: "REJECTED"
        reason: "Out of stock"
        message: "Your order could not be processed because some items are out of stock."
      then: end
  - FlagForReview:
      set:
        orderStatus: "PENDING_REVIEW"
        message: "Your order requires additional review. We will contact you shortly."
      then: end
  - HandleDeclinedPayment:
      set:
        orderStatus: "PAYMENT_DECLINED"
        reason: ${ .declineReason }
        message: "Your payment was declined. Please try a different payment method."
      then: end
  - HandlePaymentError:
      set:
        orderStatus: "PAYMENT_ERROR"
        message: "We encountered an error processing your payment. Please try again later."
      then: end
```

## Best Practices for Switch Tasks

1. **Order conditions from specific to general**: Put more specific conditions first
2. **Always include a default case**: Use `condition: true` as the last condition
3. **Preprocess data when needed**: Use a `set` task before complex switching
4. **Keep conditions readable**: Break complex conditions into separate variables
5. **Avoid deep nesting**: Prefer multiple sequential switch tasks over deeply nested logic
6. **Use meaningful task names**: Name tasks according to the decision they represent
7. **Handle all edge cases**: Consider what happens if data is missing or unexpected
8. **Document complex logic**: Add comments explaining business rules

## Next Steps

- Learn about [executing tasks in parallel](lemline-howto-parallel.md)
- Explore [running loops in workflows](lemline-howto-loops.md)
- Understand [how to use try/catch for error handling](lemline-howto-try-catch.md)