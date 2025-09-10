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
- name: DecideAction
  type: switch
  conditions:
    - condition: ".temperature > 30"
      next: HandleHotWeather
    - condition: ".temperature < 10"
      next: HandleColdWeather
    - condition: true
      next: HandleMildWeather
```

Each condition is a JQ expression that evaluates to a boolean. The first condition that evaluates to `true` determines the next task to execute.

## Simple Binary Decision

Let's start with a simple yes/no decision pattern:

```yaml
- name: CheckInventory
  type: call
  function: inventoryService
  data:
    productId: ".productId"
    quantity: ".quantity"
  next: EvaluateInventory

- name: EvaluateInventory
  type: switch
  conditions:
    - condition: ".available == true"
      next: ProcessOrder
    - condition: true
      next: HandleOutOfStock
```

This example checks inventory availability and branches to different tasks based on the result.

## Multiple Conditions

You can define multiple conditions for more complex decision trees:

```yaml
- name: CategorizeLead
  type: switch
  conditions:
    - condition: ".score >= 80"
      next: ProcessHotLead
    - condition: ".score >= 50 && .score < 80"
      next: ProcessWarmLead
    - condition: ".score >= 20 && .score < 50"
      next: ProcessCoolLead
    - condition: true
      next: ProcessColdLead
```

This example categorizes leads based on score ranges.

## Using Complex Expressions

JQ expressions in conditions can be complex logical statements:

```yaml
- name: RouteCustomerRequest
  type: switch
  conditions:
    - condition: ".type == 'refund' && .amount > 1000"
      next: EscalateToSupervisor
    - condition: ".type == 'refund' && .daysSincePurchase > 30"
      next: ApplyReturnPolicy
    - condition: ".type == 'refund'"
      next: ProcessStandardRefund
    - condition: ".type == 'exchange' && .reason == 'defective'"
      next: ProcessDefectiveExchange
    - condition: ".type == 'exchange'"
      next: ProcessStandardExchange
    - condition: true
      next: HandleGenericRequest
```

This example routes customer requests based on multiple criteria.

## Default Fallback

Always include a default condition as the last one in your switch task to ensure the workflow always has a path forward:

```yaml
- name: CheckOrderStatus
  type: switch
  conditions:
    - condition: ".status == 'approved'"
      next: ProcessApprovedOrder
    - condition: ".status == 'pending'"
      next: WaitForApproval
    - condition: ".status == 'rejected'"
      next: HandleRejectedOrder
    - condition: true
      next: HandleUnknownStatus
```

The last condition using `true` ensures there's always a matching path, even if none of the explicit conditions match.

## Working with Arrays and Objects

JQ expressions can work with complex data structures:

```yaml
- name: DetermineShippingMethod
  type: switch
  conditions:
    - condition: ".items | map(.weight) | add > 20"
      next: ArrangeFreightShipping
    - condition: ".shipping.express == true"
      next: ArrangeExpressShipping
    - condition: ".customer.tier == 'premium'"
      next: ArrangePriorityShipping
    - condition: true
      next: ArrangeStandardShipping
```

This example determines shipping method based on order weight, shipping preferences, and customer tier.

## Combining Multiple Variables

You can reference multiple variables in a single condition:

```yaml
- name: ApplyDiscount
  type: switch
  conditions:
    - condition: ".customer.loyalty_years >= 5 && .order.total > 100"
      next: ApplyPremiumDiscount
    - condition: ".customer.loyalty_years >= 2 || .order.total > 200"
      next: ApplyStandardDiscount
    - condition: ".order.items | map(select(.category == 'sale')) | length > 0"
      next: ApplySaleDiscount
    - condition: true
      next: NoDiscount
```

This example determines discounts based on customer loyalty, order total, and item categories.

## Nested Decision Making

For complex decision making, you can use multiple switch tasks in sequence:

```yaml
- name: EvaluateRiskLevel
  type: switch
  conditions:
    - condition: ".transaction.amount > 10000"
      next: HighValueCheck
    - condition: true
      next: StandardCheck

- name: HighValueCheck
  type: switch
  conditions:
    - condition: ".customer.history.flags | length > 0"
      next: ManualReview
    - condition: ".customer.age < 90"
      next: AdditionalVerification
    - condition: true
      next: AutoApprove
```

This example implements a two-level decision tree for risk evaluation.

## Using Data Transformations before Switching

You can use a `set` task to prepare data for a switch decision:

```yaml
- name: PrepareDecisionData
  type: set
  data:
    isNewCustomer: ".customer.joinDate > now() - duration('P30D')"
    isHighValue: ".order.total > 1000"
    hasRiskyItems: ".order.items | map(select(.category == 'restricted')) | length > 0"
  next: RouteOrder

- name: RouteOrder
  type: switch
  conditions:
    - condition: ".hasRiskyItems == true"
      next: ComplianceReview
    - condition: ".isNewCustomer == true && .isHighValue == true"
      next: VerificationProcess
    - condition: ".isHighValue == true"
      next: PriorityProcessing
    - condition: true
      next: StandardProcessing
```

This pattern improves readability by computing derived values before the switch.

## Error Handling in Switch Tasks

If an expression in a condition results in an error, Lemline will treat it as `false`:

```yaml
- name: ProcessPayment
  type: switch
  conditions:
    - condition: "try .paymentResult.status == 'success' catch false"
      next: CompleteOrder
    - condition: "try .paymentResult.status == 'pending' catch false"
      next: WaitForPayment
    - condition: true
      next: HandlePaymentFailure
```

This pattern handles cases where properties might be missing.

## Combining Switch with Try/Catch

For robust conditional handling, combine switch with try/catch:

```yaml
- name: ProcessOrder
  type: try
    retry:
      maxAttempts: 3
    catch:
      - error: "PAYMENT_DECLINED"
        next: HandlePaymentDeclined
      - error: "*"
        next: HandleGenericError
    do:
      - name: AttemptPayment
        type: call
        function: paymentService
        data:
          amount: ".total"
          cardToken: ".paymentMethod.token"
        next: EvaluatePayment
      
      - name: EvaluatePayment
        type: switch
        conditions:
          - condition: ".status == 'success'"
            next: CompleteOrder
          - condition: ".status == 'review'"
            next: FlagForReview
          - condition: true
            next: HandleFailure
```

This pattern provides comprehensive error handling around conditional logic.

## Real-World Example: Order Processing

Here's a complete example of an order processing workflow with multiple decision points:

```yaml
id: order-processing
name: Order Processing Workflow
version: '1.0'
specVersion: '1.0'
start: ValidateOrder
functions:
  - name: validateInventory
    type: http
    operation: GET
    url: https://inventory-api.example.com/check
  - name: calculateShipping
    type: http
    operation: POST
    url: https://shipping-api.example.com/calculate
  - name: processPayment
    type: http
    operation: POST
    url: https://payment-api.example.com/process
tasks:
  - name: ValidateOrder
    type: set
    data:
      isValid: ".items | length > 0"
      hasFulfillableItems: true
    next: CheckOrderValidity
  
  - name: CheckOrderValidity
    type: switch
    conditions:
      - condition: ".isValid == false"
        next: RejectOrder
      - condition: true
        next: CheckInventory
  
  - name: CheckInventory
    type: call
    function: validateInventory
    data:
      items: ".items | map({id: .productId, quantity: .quantity})"
    next: EvaluateInventory
  
  - name: EvaluateInventory
    type: switch
    conditions:
      - condition: ".allAvailable == true"
        next: CalculateShipping
      - condition: ".partiallyAvailable == true && .order.allowPartial == true"
        next: AdjustForPartialInventory
      - condition: true
        next: HandleOutOfStock
  
  - name: AdjustForPartialInventory
    type: set
    data:
      items: ".items | map(select(.productId as $id | .availableItems | map(.id) | contains([$id])))"
      message: "Some items were unavailable and have been removed from your order."
    next: CalculateShipping
  
  - name: CalculateShipping
    type: call
    function: calculateShipping
    data:
      destination: ".shippingAddress"
      items: ".items"
    next: DetermineShippingMethod
  
  - name: DetermineShippingMethod
    type: switch
    conditions:
      - condition: ".expressSelected == true && .expressAvailable == true"
        next: ApplyExpressShipping
      - condition: ".total.weight > 20"
        next: ApplyFreightShipping
      - condition: true
        next: ApplyStandardShipping
  
  - name: ApplyExpressShipping
    type: set
    data:
      shippingMethod: "EXPRESS"
      shippingCost: ".rates.express"
      estimatedDelivery: ".deliveryEstimates.express"
    next: CalculateTotals
  
  - name: ApplyFreightShipping
    type: set
    data:
      shippingMethod: "FREIGHT"
      shippingCost: ".rates.freight"
      estimatedDelivery: ".deliveryEstimates.freight"
    next: CalculateTotals
  
  - name: ApplyStandardShipping
    type: set
    data:
      shippingMethod: "STANDARD"
      shippingCost: ".rates.standard"
      estimatedDelivery: ".deliveryEstimates.standard"
    next: CalculateTotals
  
  - name: CalculateTotals
    type: set
    data:
      subtotal: ".items | map(.price * .quantity) | add"
      tax: ".subtotal * 0.08"
      total: ".subtotal + .tax + .shippingCost"
    next: ProcessPayment
  
  - name: ProcessPayment
    type: call
    function: processPayment
    data:
      amount: ".total"
      method: ".paymentMethod"
      currency: "USD"
    next: EvaluatePayment
  
  - name: EvaluatePayment
    type: switch
    conditions:
      - condition: ".status == 'approved'"
        next: CompleteOrder
      - condition: ".status == 'review'"
        next: FlagForReview
      - condition: ".status == 'declined'"
        next: HandleDeclinedPayment
      - condition: true
        next: HandlePaymentError
  
  - name: CompleteOrder
    type: set
    data:
      orderStatus: "CONFIRMED"
      confirmationNumber: ".transactionId"
      message: "Your order has been confirmed and will ship via .shippingMethod."
    end: true
  
  - name: RejectOrder
    type: set
    data:
      orderStatus: "REJECTED"
      reason: "Invalid order"
      message: "Your order could not be processed because it contains no items."
    end: true
  
  - name: HandleOutOfStock
    type: set
    data:
      orderStatus: "REJECTED"
      reason: "Out of stock"
      message: "Your order could not be processed because some items are out of stock."
    end: true
  
  - name: FlagForReview
    type: set
    data:
      orderStatus: "PENDING_REVIEW"
      message: "Your order requires additional review. We will contact you shortly."
    end: true
  
  - name: HandleDeclinedPayment
    type: set
    data:
      orderStatus: "PAYMENT_DECLINED"
      reason: ".declineReason"
      message: "Your payment was declined. Please try a different payment method."
    end: true
  
  - name: HandlePaymentError
    type: set
    data:
      orderStatus: "PAYMENT_ERROR"
      message: "We encountered an error processing your payment. Please try again later."
    end: true
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