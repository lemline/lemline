# Understanding Time Handling in Workflows

This document explains how time is represented, processed, and managed in Lemline workflows, including time-based operations, scheduling, and timeouts.

## Time Representation

### ISO 8601 Formats

Lemline uses the ISO 8601 standard for representing date and time values:

#### Dates

```
YYYY-MM-DD
```

Examples:
- `2023-05-15` (May 15, 2023)
- `2023-12-31` (December 31, 2023)

#### Times

```
hh:mm:ss[.sss][Z|(+|-)hh:mm]
```

Examples:
- `14:30:00` (2:30 PM)
- `09:45:30.500` (9:45:30 AM and 500 milliseconds)
- `14:30:00Z` (2:30 PM UTC)
- `14:30:00+02:00` (2:30 PM in a timezone 2 hours ahead of UTC)

#### Datetimes

```
YYYY-MM-DDThh:mm:ss[.sss][Z|(+|-)hh:mm]
```

Examples:
- `2023-05-15T14:30:00` (May 15, 2023, 2:30 PM)
- `2023-05-15T14:30:00Z` (May 15, 2023, 2:30 PM UTC)
- `2023-05-15T14:30:00+02:00` (May 15, 2023, 2:30 PM in a timezone 2 hours ahead of UTC)

#### Durations

```
P[nY][nM][nD][T[nH][nM][nS]]
```

Examples:
- `PT30S` (30 seconds)
- `PT5M` (5 minutes)
- `PT1H30M` (1 hour and 30 minutes)
- `P1DT6H` (1 day and 6 hours)
- `P1M` (1 month)
- `P1Y6M` (1 year and 6 months)

### Time Zone Handling

Lemline supports explicit time zone information:

```yaml
# Explicit UTC time
timestamp: "2023-05-15T14:30:00Z"

# Explicit offset
timestamp: "2023-05-15T14:30:00+02:00"

# Local time (interpreted as server's time zone)
timestamp: "2023-05-15T14:30:00"
```

For consistency, Lemline internally normalizes time values to UTC when performing calculations and comparisons.

## Time Operations in Workflows

### Current Time

Get the current time using the `now()` function:

```yaml
- setTimestamp:
    set:
      currentTime: "${ now() }"
      currentDate: "${ now() | format('yyyy-MM-dd') }"
      currentUtcTime: "${ nowUtc() }"
```

### Time Arithmetic

Perform time calculations using duration expressions:

```yaml
- calculateDeadlines:
    set:
      # Add 30 minutes to current time
      thirtyMinutesFromNow: "${ now() | plus('PT30M') }"
      
      # Add 1 day to current time
      tomorrowSameTime: "${ now() | plus('P1D') }"
      
      # Subtract 1 hour from current time
      oneHourAgo: "${ now() | minus('PT1H') }"
      
      # Add 3 business days (skipping weekends)
      threeBizDaysLater: "${ now() | plusBusinessDays(3) }"
```

### Time Parsing and Formatting

Parse string representations and format time values:

```yaml
- formatTimestamps:
    set:
      # Parse string to datetime
      parsedTime: "${ fromIso8601('2023-05-15T14:30:00Z') }"
      
      # Format datetime as string
      formattedDate: "${ now() | format('yyyy-MM-dd') }"
      formattedTime: "${ now() | format('HH:mm:ss') }"
      customFormat: "${ now() | format('EEEE, MMMM d, yyyy') }"
```

### Time Comparisons

Compare time values:

```yaml
- checkDeadlines:
    if: "${ now() > .deadline }"
    do:
      - handleMissedDeadline:
          # Handle missed deadline
    
    if: "${ .startTime <= now() && now() <= .endTime }"
    do:
      - handleDuringActiveHours:
          # Handle during active hours
```

### Time Extraction

Extract components from time values:

```yaml
- extractTimeComponents:
    set:
      year: "${ now() | year() }"
      month: "${ now() | month() }"
      day: "${ now() | day() }"
      hour: "${ now() | hour() }"
      minute: "${ now() | minute() }"
      second: "${ now() | second() }"
      dayOfWeek: "${ now() | dayOfWeek() }"
```

## Waiting and Timeouts

### Wait Task

The `wait` task suspends workflow execution for a specified duration or until a specific time:

```yaml
# Wait for a duration
- waitShort:
    wait:
      duration: PT30S  # Wait for 30 seconds

# Wait until a specific time
- waitUntil:
    wait:
      until: "2023-12-31T23:59:59Z"  # Wait until the specified time

# Wait with dynamic expression
- waitDynamic:
    wait:
      duration: "${ .customWaitTime }"  # Use a variable for wait duration
```

### Timeouts

Timeouts prevent operations from blocking indefinitely:

#### Task Timeouts

```yaml
# Task-level timeout
- fetchData:
    callHTTP:
      url: "https://api.example.com/data"
      method: "GET"
      timeout: PT30S  # 30-second timeout for HTTP call
```

#### Workflow Timeouts

```yaml
# Workflow-level timeout
workflow:
  timeout: PT1H  # 1-hour timeout for entire workflow
```

#### Event Waiting Timeouts

```yaml
# Timeout for waiting for events
- waitForApproval:
    listen:
      to: "any"
      events:
        - event: "OrderApproved"
        - event: "OrderRejected"
      timeout: PT24H  # Wait up to 24 hours for an event
```

### Timeout Handling

Handle timeout errors gracefully:

```yaml
- fetchData:
    try:
      do:
        - callApi:
            callHTTP:
              url: "https://api.example.com/data"
              method: "GET"
              timeout: PT10S
      catch:
        - error:
            with:
              type: "https://serverlessworkflow.io/spec/1.0.0/errors/timeout"
            as: "timeoutError"
          do:
            - handleTimeout:
                set:
                  status: "timeout"
                  message: "API call timed out"
                  fallbackData: "${ .defaultData }"
```

## Scheduling and Timers

### Cron-Based Scheduling

Schedule workflows using cron expressions:

```yaml
# Workflow trigger definition
schedule:
  cron: "0 0 * * *"  # Run daily at midnight
```

Common cron patterns:
- `0 0 * * *` - Daily at midnight
- `0 */6 * * *` - Every 6 hours
- `0 9-17 * * 1-5` - Every hour from 9 AM to 5 PM, Monday through Friday
- `0 0 1 * *` - Monthly on the 1st day

### Time-Based Events

Wait for time-based events:

```yaml
- waitForBusinessHours:
    listen:
      to: "any"
      events:
        - event: "BusinessHoursStart"
          filter: "${ .date == today() }"
        - event: "ManualOverride"
      timeout: PT12H
```

### Delayed Execution

Schedule tasks for delayed execution:

```yaml
# Emit a delayed event
- scheduleReminder:
    emit:
      event: "OrderReminder"
      data: "${ .order }"
      delayedUntil: "${ now() | plus('P3D') }"  # 3 days from now
```

## Business Time Concepts

### Business Days

Handle business day calculations:

```yaml
- calculateDeliveryDate:
    set:
      # Add 3 business days to current date
      estimatedDelivery: "${ today() | plusBusinessDays(3) }"
      
      # Check if date is a business day
      isBusinessDay: "${ isBusinessDay(.deliveryDate) }"
      
      # Get next business day
      nextBusinessDay: "${ nextBusinessDay(.date) }"
```

### Business Hours

Work with business hours:

```yaml
- checkBusinessHours:
    set:
      # Define business hours
      businessHoursStart: "09:00:00"
      businessHoursEnd: "17:00:00"
      
      # Check if current time is within business hours
      isDuringBusinessHours: "${ isWithinHours(now(), .businessHoursStart, .businessHoursEnd) }"
      
      # Calculate time until next business hours
      timeUntilBusinessHours: "${ timeUntilHours(now(), .businessHoursStart, .businessHoursEnd) }"
```

### Holiday Handling

Handle holidays in time calculations:

```yaml
- adjustForHolidays:
    set:
      # Define holidays
      holidays: [
        "2023-01-01",  # New Year's Day
        "2023-07-04",  # Independence Day
        "2023-12-25"   # Christmas
      ]
      
      # Check if date is a holiday
      isHoliday: "${ contains(.holidays, today()) }"
      
      # Adjust delivery date for holidays
      adjustedDelivery: "${ adjustForHolidays(.estimatedDelivery, .holidays) }"
```

## Time Zones and Internationalization

### Explicit Time Zone Handling

Work with different time zones:

```yaml
- handleTimeZones:
    set:
      # Convert time between time zones
      localTime: "${ .utcTime | toTimeZone('America/New_York') }"
      utcTime: "${ .localTime | toUtc() }"
      
      # Format with time zone
      formattedWithTz: "${ now() | formatWithZone('yyyy-MM-dd HH:mm:ss z', 'Europe/Paris') }"
```

### Time Zone Awareness

Be explicit about time zone handling:

```yaml
- scheduleMeeting:
    set:
      # Define meeting in a specific time zone
      meetingTime: "2023-06-15T14:00:00"
      meetingTimeZone: "America/New_York"
      
      # Convert to participant time zones
      londonTime: "${ fromTimeZone(.meetingTime, .meetingTimeZone) | toTimeZone('Europe/London') }"
      tokyoTime: "${ fromTimeZone(.meetingTime, .meetingTimeZone) | toTimeZone('Asia/Tokyo') }"
      
      # Format for participants
      londonFormatted: "${ .londonTime | format('yyyy-MM-dd HH:mm') } (London)"
      tokyoFormatted: "${ .tokyoTime | format('yyyy-MM-dd HH:mm') } (Tokyo)"
```

## Common Time Patterns

### Debouncing

Implement debouncing to handle frequent events:

```yaml
- debounceEvents:
    listen:
      to: "any"
      events:
        - event: "UserActivity"
      consume:
        debounce: PT5S  # Collect events for 5 seconds before processing
```

### Throttling

Implement throttling to limit request rates:

```yaml
- throttleRequests:
    set:
      lastRequestTime: "${ .lastRequestTime || '1970-01-01T00:00:00Z' }"
      minimumInterval: "PT1S"
      
      canMakeRequest: "${ now() | minus(.lastRequestTime) >= .minimumInterval }"
      
    if: "${ .canMakeRequest }"
    do:
      - makeRequest:
          callHTTP:
            url: "https://rate-limited-api.example.com/data"
            method: "GET"
          
          set:
            lastRequestTime: "${ now() }"
```

### Rate Limiting

Implement rate limiting across workflow instances:

```yaml
- checkRateLimit:
    extension:
      rateLimit:
        key: "api-calls"
        limit: 100
        window: PT1M
        
    if: "${ .rateLimited }"
    do:
      - handleRateLimited:
          wait:
            duration: "${ .retryAfter }"
```

### Time Windows

Process data in time windows:

```yaml
- processTimeWindow:
    set:
      windowStart: "${ .lastProcessedTime || (now() | minus('PT1H')) }"
      windowEnd: "${ now() }"
      
    callHTTP:
      url: "https://api.example.com/data"
      method: "GET"
      query:
        startTime: "${ .windowStart | format('yyyy-MM-dd\'T\'HH:mm:ss\'Z\'') }"
        endTime: "${ .windowEnd | format('yyyy-MM-dd\'T\'HH:mm:ss\'Z\'') }"
      
    set:
      lastProcessedTime: "${ .windowEnd }"
```

## Time-Related Error Handling

### Retry with Backoff

Implement time-based retry strategies:

```yaml
- reliableOperation:
    try:
      retry:
        policy:
          strategy: backoff
          backoff:
            delay: PT1S
            multiplier: 2
            jitter: 0.1
          limit:
            attempt:
              count: 5
            duration: PT1M
      do:
        - performOperation:
            # Operation that might fail
```

### Deadline Handling

Handle approaching deadlines:

```yaml
- checkDeadline:
    set:
      timeUntilDeadline: "${ .deadline | minus(now()) }"
      
    switch:
      - condition: "${ .timeUntilDeadline <= 'PT0S' }"
        do:
          - handleMissedDeadline:
              # Handle missed deadline
      
      - condition: "${ .timeUntilDeadline <= 'PT1H' }"
        do:
          - handleUrgent:
              # Handle approaching deadline
      
      - otherwise:
          do:
            - handleNormal:
                # Normal processing
```

## Best Practices

### Time Representation

1. **Use ISO 8601**: Always use ISO 8601 for time representation
2. **Be Explicit About Time Zones**: Explicitly include time zone information
3. **Use UTC Internally**: Store and process times in UTC when possible
4. **Format for Display**: Format times appropriately for human readability

### Time Calculations

1. **Use Built-in Functions**: Leverage built-in time functions for calculations
2. **Handle Time Zones Carefully**: Be aware of time zone conversions
3. **Consider DST**: Be aware of Daylight Saving Time transitions
4. **Use Time Spans**: Work with durations rather than calculating with timestamps

### Error Handling

1. **Set Reasonable Timeouts**: Avoid indefinite waiting with appropriate timeouts
2. **Handle Timeout Errors**: Explicitly catch and handle timeout errors
3. **Implement Retries**: Use retry with backoff for time-sensitive operations
4. **Provide Fallbacks**: Define fallback behavior when time constraints cannot be met

## Related Resources

- [Wait Task Reference](dsl-task-wait.md)
- [Timeouts Reference](dsl-timeouts.md)
- [Error Handling](lemline-explain-errors.md)
- [JQ Expressions Guide](lemline-howto-jq.md)
- [Resilience Patterns](dsl-resilience-patterns.md)