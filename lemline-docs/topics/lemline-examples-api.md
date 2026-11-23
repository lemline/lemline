# API Integration Examples

> **Runtime support:** some examples on this page use `call: openapi`, `call: asyncapi`, `call: grpc` or OAuth 2.0 authentication. The Lemline runtime does not execute these yet: such a task fails when it runs. Write those calls with `call: http` and `basic` or `bearer` authentication to run them on Lemline.


This document provides practical examples of API integrations in Lemline workflows. It demonstrates different approaches to connecting with REST APIs, GraphQL, OpenAPI, gRPC, and other API types, covering authentication, error handling, and data transformation.

## REST API Integration Examples

### Basic REST API Workflow

This example demonstrates a simple workflow that interacts with multiple REST endpoints.

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: user-registration
  version: 1.0.0
input:
  schema:
    format: json
    document:
      type: object
      required:
        - email
        - name
      properties:
        email:
          type: string
          format: email
        name:
          type: string
use:
  secrets:
    - API_KEY
    - EMAIL_SERVICE_TOKEN
do:
  - validateEmail:
      call: http
      with:
        method: post
        endpoint: https://validation.example/api/verify-email
        body:
          email: ${ .email }
      output:
        as: '${ $input + { "emailValidation": . } }'
  - createUserAccount:
      switch:
        - validEmail:
            when: ${ .emailValidation.valid == true }
            then: createUserAccount-validEmail
        - default:
            then: createUserAccount-default
  - createUserAccount-validEmail:
      do:
        - registerUser:
            call: http
            with:
              method: post
              endpoint: https://accounts.example/api/users
              headers:
                Content-Type: application/json
                X-API-Key: ${ $secrets.API_KEY }
              body:
                email: ${ .email }
                name: ${ .name }
                verificationStatus: "verified"
            output:
              as: '${ $input + { "userAccount": . } }'
        - sendWelcomeEmail:
            call: http
            with:
              method: post
              endpoint: https://notifications.example/api/emails
              headers:
                Content-Type: application/json
                Authorization: Bearer ${ $secrets.EMAIL_SERVICE_TOKEN }
              body:
                template: "welcome"
                recipient: ${ .email }
                data:
                  name: ${ .name }
                  accountId: ${ .userAccount.id }
      then: createUserAccount-done
  - createUserAccount-default:
      do:
        - notifyInvalidEmail:
            set:
              result:
                success: false
                reason: "Invalid email address"
                details: ${ .emailValidation.reason }
      then: createUserAccount-done
  - createUserAccount-done:
      set: ${ . }
```

### Pagination Handling

This example shows how to handle paginated API responses.

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: list-all-users
  version: 1.0.0
do:
  - initializeUserList:
      set:
        allUsers: []
        nextPage: 1
        hasMorePages: true
      export:
        as: '${ $context + { allUsers: [], nextPage: 1, hasMorePages: true } }'

  - fetchAllPages:
      do:
        - iterate:
            do:
              - fetchNextUserPage:
                  call: http
                  with:
                    method: get
                    endpoint: https://api.example/users
                    query:
                      page: ${ $context.nextPage }
                      limit: '100'
                  output:
                    as: '${ $input + { "pageResult": . } }'
              - processPage:
                  set:
                    allUsers: ${ $context.allUsers + .pageResult.users }
                    nextPage: ${ $context.nextPage + 1 }
                    hasMorePages: ${ .pageResult.hasMore }
                  export:
                    as: '${ $context + { allUsers: ($context.allUsers + .pageResult.users), nextPage: ($context.nextPage +
                      1), hasMorePages: (.pageResult.hasMore)} }'

        - repeat:
            switch:
              - more:
                  when: ${ $context.hasMorePages }
                  then: iterate
              - done:
                  then: exit
  - processFinalResult:
      set:
        result:
          totalUsers: ${ ($context.allUsers | length) }
          users: ${ $context.allUsers }
```

### Request Throttling

This example demonstrates how to implement API request throttling to respect rate limits.

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: api-throttling
  version: 1.0.0
input:
  schema:
    format: json
    document:
      type: object
      required:
        - items
      properties:
        items:
          type: array
          items:
            type: string
use:
  secrets:
    - API_TOKEN
do:
  - initializeContext:
      set:
        processedItems: []
        failures: []
      export:
        as: '${ $context + { processedItems: [], failures: [] } }'

  - processBatches:
      for:
        in: ${ .items as $items | [range(0; ($items | length); 5) as $i | $items[$i:$i+5]] } # Process in batches of 5 items
        each: batch
      do:
        - processBatch:
            for:
              in: ${ $batch }
              each: item
            do:
              - processItem:
                  try:
                    - callRateLimitedAPI:
                        call: http
                        with:
                          method: get
                          endpoint: ${ "https://rate-limited-api.example/items/\($item)" }
                          headers:
                            Authorization: Bearer ${ $secrets.API_TOKEN }
                        export:
                          as: '${ $context + { processedItems: ($context.processedItems + [.itemResult])} }'
                        output:
                          as: '${ $input + { "itemResult": . } }'
                  catch:
                    as: failure
                    when: ${ ($failure.type == "https://open-workflow-specification.org/spec/1.0.0/errors/communication"
                      and $failure.status == 429) or ($failure.type ==
                      "https://open-workflow-specification.org/spec/1.0.0/errors/communication") }
                    do:
                      - selectHandler:
                          switch:
                            - case1:
                                then: handle1
                                when: ${ $failure.type ==
                                  "https://open-workflow-specification.org/spec/1.0.0/errors/communication" and
                                  $failure.status == 429 }
                            - case2:
                                then: handle2
                                when: ${ $failure.type ==
                                  "https://open-workflow-specification.org/spec/1.0.0/errors/communication" }
                      - handle1:
                          set: ${ . }
                          then: exit
                      - handle2:
                          set: ${ . }
                          then: exit
        - throttleBetweenBatches:
            wait:
              seconds: 2    # Wait 2 seconds between batches

  - summarizeResults:
      set:
        result:
          succeeded: ${ ($context.processedItems | length) }
          failed: ${ ($context.failures | length) }
          processedItems: ${ $context.processedItems }
          failures: ${ $context.failures }
```

## OpenAPI Integration Examples

### OpenAPI Specification Integration

This example demonstrates how to use an OpenAPI specification for API integration.

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: pet-store-integration
  version: 1.0.0
do:
  - listAvailablePets:
      call: openapi
      with:
        document:
          endpoint: https://petstore.swagger.io/v2/swagger.json
        operationId: findPetsByStatus
        parameters:
          status: available
      output:
        as: '${ $input + { "availablePets": . } }'
  - addNewPet:
      call: openapi
      with:
        document:
          endpoint: https://petstore.swagger.io/v2/swagger.json
        operationId: addPet
        parameters:
          body:
            name: "Fluffy"
            status: "available"
            category:
              id: 1
              name: "Dogs"
            tags:
              - id: 1
                name: "friendly"
      output:
        as: '${ $input + { "newPet": . } }'
  - retrievePet:
      call: openapi
      with:
        document:
          endpoint: https://petstore.swagger.io/v2/swagger.json
        operationId: getPetById
        parameters:
          petId: ${ .newPet.id }
      output:
        as: '${ $input + { "retrievedPet": . } }'
```

### OpenAPI with Authentication

This example shows OpenAPI integration with different authentication methods.

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: openapi-auth
  version: 1.0.0
use:
  secrets:
    - API_KEY
    - CLIENT_ID
    - CLIENT_SECRET
    - USERNAME
    - PASSWORD
do:
  - callWithApiKey:
      call: openapi
      with:
        document:
          endpoint: https://api.example/openapi.json
        operationId: getResources
        parameters:
          "X-API-Key": ${ $secrets.API_KEY }
      output:
        as: '${ $input + { "apiKeyResult": . } }'
  - callWithOAuth2:
      call: openapi
      with:
        document:
          endpoint: https://api.example/openapi.json
        operationId: createResource
        authentication:
          oauth2:
            authority: https://auth.example/oauth2
            grant: client_credentials
            client:
              id: ${ $secrets.CLIENT_ID }
              secret: ${ $secrets.CLIENT_SECRET }
            scopes: ["resource.write"]
        parameters:
          body:
            name: "Example Resource"
            type: "test"
      output:
        as: '${ $input + { "oauth2Result": . } }'
  - callWithBasicAuth:
      call: openapi
      with:
        document:
          endpoint: https://api.example/openapi.json
        operationId: deleteResource
        authentication:
          basic:
            username: ${ $secrets.USERNAME }
            password: ${ $secrets.PASSWORD }
        parameters:
          resourceId: ${ .oauth2Result.id }
      output:
        as: '${ $input + { "basicAuthResult": . } }'
```

## GraphQL Integration Examples

### Basic GraphQL Query

This example demonstrates a basic GraphQL query using HTTP integration.

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: graphql-query
  version: 1.0.0
input:
  schema:
    format: json
    document:
      type: object
      required:
        - userId
      properties:
        userId:
          type: string
use:
  secrets:
    - API_TOKEN
do:
  - fetchUserData:
      call: http
      with:
        method: post
        endpoint: https://api.example/graphql
        headers:
          Content-Type: application/json
          Authorization: Bearer ${ $secrets.API_TOKEN }
        body:
          query: |
            query GetUser($id: ID!) {
              user(id: $id) {
                id
                name
                email
                posts {
                  id
                  title
                  createdAt
                }
                followers {
                  totalCount
                }
              }
            }
          variables:
            id: ${ .userId }
      output:
        as: '${ $input + { "graphqlResponse": . } }'
  - processUserData:
      set:
        userData: ${ .graphqlResponse.data.user }
        totalPosts: ${ (.graphqlResponse.data.user.posts | length) }
        followerCount: ${ .graphqlResponse.data.user.followers.totalCount }
```

### GraphQL Mutation

This example demonstrates a GraphQL mutation.

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: graphql-mutation
  version: 1.0.0
input:
  schema:
    format: json
    document:
      type: object
      required:
        - postData
      properties:
        postData:
          type: object
          required:
            - title
            - content
use:
  secrets:
    - API_TOKEN
do:
  - createPost:
      call: http
      with:
        method: post
        endpoint: https://api.example/graphql
        headers:
          Content-Type: application/json
          Authorization: Bearer ${ $secrets.API_TOKEN }
        body:
          query: |
            mutation CreatePost($input: CreatePostInput!) {
              createPost(input: $input) {
                post {
                  id
                  title
                  content
                  createdAt
                }
                errors {
                  field
                  message
                }
              }
            }
          variables:
            input:
              title: ${ .postData.title }
              content: ${ .postData.content }
      output:
        as: '${ $input + { "graphqlResponse": . } }'
  - processResult:
      switch:
        - hasErrors:
            when: ${ .graphqlResponse.data.createPost.errors != null and (.graphqlResponse.data.createPost.errors |
              length) > 0 }
            then: processResult-hasErrors
        - default:
            then: processResult-default
  - processResult-hasErrors:
      do:
        - handleErrors:
            set:
              result:
                success: false
                errors: ${ .graphqlResponse.data.createPost.errors }
      then: processResult-done
  - processResult-default:
      do:
        - handleSuccess:
            set:
              result:
                success: true
                post: ${ .graphqlResponse.data.createPost.post }
      then: processResult-done
  - processResult-done:
      set: ${ . }
```

## gRPC Integration Examples

### Basic gRPC Service Call

This example demonstrates integrating with a gRPC service.

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: grpc-integration
  version: 1.0.0
input:
  schema:
    format: json
    document:
      type: object
      required:
        - username
      properties:
        username:
          type: string
do:
  - greetUser:
      call: grpc
      with:
        proto:
          endpoint: file:///protos/greeter.proto
        service:
          name: Greeter
          host: grpc.example
          port: 50051
        method: SayHello
        arguments:
          name: ${ .username }
      output:
        as: '${ $input + { "greeting": . } }'
  - getUserProfile:
      call: grpc
      with:
        proto:
          endpoint: file:///protos/user_service.proto
        service:
          name: UserService
          host: grpc.example
          port: 50051
        method: GetUserProfile
        arguments:
          username: ${ .username }
      output:
        as: '${ $input + { "userProfile": . } }'
  - combineResults:
      set:
        result:
          greeting: ${ .greeting.message }
          profile:
            userId: ${ .userProfile.id }
            displayName: ${ .userProfile.displayName }
            email: ${ .userProfile.email }
            joinDate: ${ .userProfile.joinDate }
```

### gRPC with TLS and Authentication

This example demonstrates gRPC integration with TLS and JWT authentication.

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: secure-grpc
  version: 1.0.0
use:
  secrets:
    - CLIENT_ID
    - CLIENT_SECRET
do:
  - getAuthToken:
      call: http
      with:
        method: post
        endpoint: https://auth.example/token
        body:
          clientId: ${ $secrets.CLIENT_ID }
          clientSecret: ${ $secrets.CLIENT_SECRET }
          scope: "api:access"
      output:
        as: '${ $input + { "authResponse": . } }'
  - secureGrpcCall:
      call: grpc
      with:
        proto:
          endpoint: file:///protos/secure_service.proto
        service:
          name: SecureService
          host: api.example
          port: 443
          authentication:
            bearer:
              token: ${ .authResponse.access_token }
        method: GetSecureData
        arguments:
          requestId: ${ $workflow.id }
          timestamp: ${ now }
      output:
        as: '${ $input + { "secureData": . } }'
      metadata:
        transport: Configure TLS at the service endpoint.
  - processSecureData:
      set:
        result:
          data: ${ .secureData.items }
          count: ${ (.secureData.items | length) }
          timestamp: ${ .secureData.timestamp }
```

## AsyncAPI Integration Examples

### Event-Driven API Integration

This example demonstrates AsyncAPI integration for event-driven architectures.

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: event-driven-workflow
  version: 1.0.0
do:
  - listenForOrder:
      call: asyncapi
      with:
        document:
          endpoint: file:///apis/order-service.asyncapi.json
        operation: receiveOrder
        subscription:
          filter: '${ .type == "new-order" }'
          consume:
            amount: 1
      output:
        as: '${ $input + { "order": . } }'
  - processOrder:
      call: http
      with:
        method: post
        endpoint: https://inventory.example/api/check-availability
        body:
          orderId: '${ .order.id }'
          items: '${ .order.items }'
      output:
        as: '${ $input + { "availabilityCheck": . } }'
  - publishOrderStatus:
      call: asyncapi
      with:
        document:
          endpoint: file:///apis/order-service.asyncapi.json
        operation: publishOrderStatus
        message:
          payload:
            orderId: '${ .order.id }'
            status: '${ if .availabilityCheck.allItemsAvailable then "confirmed" else "backorder" end }'
            availableItems: '${ .availabilityCheck.availableItems }'
            unavailableItems: '${ .availabilityCheck.unavailableItems }'
```

### Message Streaming with AsyncAPI

This example shows how to process streams of messages with AsyncAPI.

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: stream-processing
  version: 1.0.0
do:
  - processTelemetryStream:
      call: asyncapi
      with:
        document:
          endpoint: file:///apis/telemetry.asyncapi.json
        operation: subscribeTelemetry
        subscription:
          filter: '${ .deviceType == "sensor" }'
          consume:
            while: ${ true }
          foreach:
            do:
              - processTelemetryItem:
                  try:
                    - analyzeTelemetry:
                        call: http
                        with:
                          method: post
                          endpoint: https://analytics.example/api/analyze
                          body: ${ . }
                        output:
                          as: '${ $input + { "analysis": . } }'
                    - checkForAlert:
                        switch:
                          - alertCondition:
                              when: ${ .analysis.alertRequired == true }
                              then: checkForAlert-alertCondition
                    - checkForAlert-alertCondition:
                        do:
                          - triggerAlert:
                              call: asyncapi
                              with:
                                document:
                                  endpoint: file:///apis/telemetry.asyncapi.json
                                operation: publishAlert
                                message:
                                  payload:
                                    deviceId: ${ .deviceId }
                                    alertType: ${ .analysis.alertType }
                                    severity: ${ .analysis.severity }
                                    value: ${ .value }
                                    timestamp: ${ .timestamp }
                        then: checkForAlert-done
                    - checkForAlert-done:
                        set: ${ . }
                  catch:
                    errors:
                      with:
                        type: https://open-workflow-specification.org/spec/1.0.0/errors/communication
                    retry:
                      delay:
                        seconds: 2
                      limit:
                        attempt:
                          count: 3
```

## API Integration Best Practices

When implementing API integrations in Lemline workflows, consider these best practices:

1. **Authentication Management**: Use secrets for managing API credentials securely
2. **Request Validation**: Validate input data before making API calls
3. **Response Validation**: Validate API responses against expected schemas
4. **Error Handling**: Implement robust error handling with appropriate retry policies
5. **Rate Limiting**: Respect API rate limits by implementing throttling or backoff strategies
6. **Pagination**: Handle paginated API responses properly
7. **Idempotency**: Design workflows to handle API call retries safely
8. **Circuit Breaking**: Implement circuit breaking for failing endpoints
9. **Monitoring**: Include appropriate logging for API interactions
10. **Timeout Handling**: Set appropriate timeouts for API calls (Lemline only enforces timeouts on `listen` tasks; HTTP calls use fixed limits of 15 s to connect and 30 s per request)

## Conclusion

These examples demonstrate how to implement various API integration patterns in Lemline workflows. By leveraging the Open Workflow DSL's capabilities, you can build robust API integrations that handle authentication, error conditions, pagination, and other common challenges.

Whether you're working with REST APIs, GraphQL, OpenAPI specifications, gRPC services, or event-driven AsyncAPI definitions, Lemline provides a consistent approach to API integration that emphasizes reliability, security, and maintainability.

For more examples of error handling in API integrations, see the [Error Handling Examples](lemline-examples-error-handling.md) document.
