# API Integration Examples

This document provides practical examples of API integrations in Lemline workflows. It demonstrates different approaches to connecting with REST APIs, GraphQL, OpenAPI, gRPC, and other API types, covering authentication, error handling, and data transformation.

## REST API Integration Examples

### Basic REST API Workflow

This example demonstrates a simple workflow that interacts with multiple REST endpoints.

```yaml
document:
  dsl: 1.0.0
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
do:
  - validateEmail:
      call: http
      with:
        method: post
        endpoint: https://validation.example/api/verify-email
        body:
          email: ${ .email }
        output: emailValidation
  
  - createUserAccount:
      switch:
        - validEmail:
            when: ${ .emailValidation.valid == true }
            then:
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
                      output: userAccount
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
        - default:
            then:
              do:
                - notifyInvalidEmail:
                    set:
                      result:
                        success: false
                        reason: "Invalid email address"
                        details: ${ .emailValidation.reason }
```

### Pagination Handling

This example shows how to handle paginated API responses.

```yaml
document:
  dsl: 1.0.0
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
        as: '$context + { allUsers: [], nextPage: 1, hasMorePages: true }'

  - fetchAllPages:
      while: ${ $context.hasMorePages }
      do:
        - fetchNextUserPage:
            call: http
            with:
              method: get
              endpoint: https://api.example/users
              query:
                page: ${ $context.nextPage }
                limit: 100
              output: pageResult
        - processPage:
            set:
              allUsers: ${ $context.allUsers + .pageResult.users }
              nextPage: ${ $context.nextPage + 1 }
              hasMorePages: ${ .pageResult.hasMore }
            export:
              as: '$context + { allUsers: $context.allUsers + .pageResult.users, nextPage: $context.nextPage + 1, hasMorePages: .pageResult.hasMore }'

  - processFinalResult:
      set:
        result:
          totalUsers: ${ length($context.allUsers) }
          users: ${ $context.allUsers }
```

### Request Throttling

This example demonstrates how to implement API request throttling to respect rate limits.

```yaml
document:
  dsl: 1.0.0
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
do:
  - initializeContext:
      set:
        processedItems: []
        failures: []
      export:
        as: '$context + { processedItems: [], failures: [] }'

  - processBatches:
      for:
        in: ${ chunk(.items, 5) }  # Process in batches of 5 items
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
                          endpoint: https://rate-limited-api.example/items/${$item}
                          headers:
                            Authorization: Bearer ${ $secrets.API_TOKEN }
                          output: itemResult
                        export:
                          as: '$context + { processedItems: $context.processedItems + [.itemResult] }'
                  catch:
                    - errors:
                        with:
                          type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
                          status: 429  # Too Many Requests
                      retry:
                        delay:
                          seconds: 10
                        backoff:
                          exponential: {}
                        limit:
                          attempt:
                            count: 3
                    - errors:
                        with:
                          type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
                      as: apiError
                      export:
                        as: '$context + { failures: $context.failures + [{ item: $item, error: $apiError }] }'
        - throttleBetweenBatches:
            wait:
              duration:
                seconds: 2  # Wait 2 seconds between batches

  - summarizeResults:
      set:
        result:
          succeeded: ${ length($context.processedItems) }
          failed: ${ length($context.failures) }
          processedItems: ${ $context.processedItems }
          failures: ${ $context.failures }
```

## OpenAPI Integration Examples

### OpenAPI Specification Integration

This example demonstrates how to use an OpenAPI specification for API integration.

```yaml
document:
  dsl: 1.0.0
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
        output: availablePets

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
        output: newPet

  - retrievePet:
      call: openapi
      with:
        document:
          endpoint: https://petstore.swagger.io/v2/swagger.json
        operationId: getPetById
        parameters:
          petId: ${ .newPet.id }
        output: retrievedPet
```

### OpenAPI with Authentication

This example shows OpenAPI integration with different authentication methods.

```yaml
document:
  dsl: 1.0.0
  namespace: examples
  name: openapi-auth
  version: 1.0.0
do:
  - callWithApiKey:
      call: openapi
      with:
        document:
          endpoint: https://api.example/openapi.json
        operationId: getResources
        authentication:
          apiKey:
            name: "X-API-Key"
            in: "header"
            key: ${ $secrets.API_KEY }
        output: apiKeyResult

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
        output: oauth2Result

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
        output: basicAuthResult
```

## GraphQL Integration Examples

### Basic GraphQL Query

This example demonstrates a basic GraphQL query using HTTP integration.

```yaml
document:
  dsl: 1.0.0
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
        output: graphqlResponse

  - processUserData:
      set:
        userData: ${ .graphqlResponse.data.user }
        totalPosts: ${ length(.graphqlResponse.data.user.posts) }
        followerCount: ${ .graphqlResponse.data.user.followers.totalCount }
```

### GraphQL Mutation

This example demonstrates a GraphQL mutation.

```yaml
document:
  dsl: 1.0.0
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
        output: graphqlResponse

  - processResult:
      switch:
        - hasErrors:
            when: ${ .graphqlResponse.data.createPost.errors != null and length(.graphqlResponse.data.createPost.errors) > 0 }
            then:
              do:
                - handleErrors:
                    set:
                      result:
                        success: false
                        errors: ${ .graphqlResponse.data.createPost.errors }
        - default:
            then:
              do:
                - handleSuccess:
                    set:
                      result:
                        success: true
                        post: ${ .graphqlResponse.data.createPost.post }
```

## gRPC Integration Examples

### Basic gRPC Service Call

This example demonstrates integrating with a gRPC service.

```yaml
document:
  dsl: 1.0.0
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
        output: greeting

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
        output: userProfile

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
  dsl: 1.0.0
  namespace: examples
  name: secure-grpc
  version: 1.0.0
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
        output: authResponse

  - secureGrpcCall:
      call: grpc
      with:
        proto:
          endpoint: file:///protos/secure_service.proto
        service:
          name: SecureService
          host: api.example
          port: 443
          tls: true
        method: GetSecureData
        arguments:
          requestId: ${ uuidv4() }
          timestamp: ${ now() }
        metadata:
          authorization: Bearer ${ .authResponse.access_token }
        output: secureData

  - processSecureData:
      set:
        result:
          data: ${ .secureData.items }
          count: ${ length(.secureData.items) }
          timestamp: ${ .secureData.timestamp }
```

## AsyncAPI Integration Examples

### Event-Driven API Integration

This example demonstrates AsyncAPI integration for event-driven architectures.

```yaml
document:
  dsl: 1.0.0
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
        output: order

  - processOrder:
      call: http
      with:
        method: post
        endpoint: https://inventory.example/api/check-availability
        body:
          orderId: ${ .order.id }
          items: ${ .order.items }
        output: availabilityCheck

  - publishOrderStatus:
      call: asyncapi
      with:
        document:
          endpoint: file:///apis/order-service.asyncapi.json
        operation: publishOrderStatus
        message:
          payload:
            orderId: ${ .order.id }
            status: ${ .availabilityCheck.allItemsAvailable ? "confirmed" : "backorder" }
            availableItems: ${ .availabilityCheck.availableItems }
            unavailableItems: ${ .availabilityCheck.unavailableItems }
```

### Message Streaming with AsyncAPI

This example shows how to process streams of messages with AsyncAPI.

```yaml
document:
  dsl: 1.0.0
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
            forever:
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
                              output: analysis
                        - checkForAlert:
                            switch:
                              - alertCondition:
                                  when: ${ .analysis.alertRequired == true }
                                  then:
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
                      catch:
                        errors:
                          with:
                            type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
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
10. **Timeout Handling**: Set appropriate timeouts for API calls

## Conclusion

These examples demonstrate how to implement various API integration patterns in Lemline workflows. By leveraging the Serverless Workflow DSL's capabilities, you can build robust API integrations that handle authentication, error conditions, pagination, and other common challenges.

Whether you're working with REST APIs, GraphQL, OpenAPI specifications, gRPC services, or event-driven AsyncAPI definitions, Lemline provides a consistent approach to API integration that emphasizes reliability, security, and maintainability.

For more examples of error handling in API integrations, see the [Error Handling Examples](lemline-examples-error-handling.md) document.