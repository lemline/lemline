---
title: Authentication
---
<!-- Examples are validated -->

# Authentication

## Purpose

Authentication definitions in Serverless Workflow specify the methods and credentials required for a workflow to
securely access protected external resources or services. This allows workflows to interact with APIs, databases,
message brokers, or other systems that require identity verification.

Authentication can be defined in two main places:

1. **Globally**: Under the `use.authentications` section of the workflow definition. This creates named, reusable
   authentication policies.
2. **Inline**: Directly within the configuration of a task or resource that requires authentication (e.g., inside an
   `endpoint` object for an HTTP call or a Resource Catalog).

## Defining Reusable Authentications (`use.authentications`)

Defining authentications globally under `use.authentications` promotes reusability and separates sensitive details (
potentially using secrets) from the task logic.

```yaml
document:
  dsl: '1.0.0'
  namespace: auth-examples
  name: reusable-auth-workflow
  version: '1.0.0'
use:
  secrets:
    - myApiKeySecret
    - myOauthClientSecret
  # Define named authentication policies
  authentications:
    # Basic Auth using secrets
    mySystemBasicAuth:
      basic:
        username: service-account
        password: ${ $secrets.myApiKeySecret }
    # OAuth2 Client Credentials
    myApiClientOAuth:
      oauth2:
        authority: https://auth.example.com
        grant: client_credentials
        client:
          id: workflow-client-id
          secret: ${ $secrets.myOauthClientSecret }
        scopes: [ read:data, write:data ]
    # Simple Bearer Token (potentially from context)
    userBearerTokenAuth:
      bearer:
        token: ${ $secrets.userProvidedToken }
    # Digest Auth (Requires username/password, often from secrets)
    myDigestServiceAuth:
      digest:
        username: digest-user
        password: ${ $secrets.digestPassword }
    # OIDC (Conceptual - structure similar to OAuth2, often relies on discovery)
    myOidcProviderAuth:
      oidc:
        authority: https://oidc.example.com/.well-known/openid-configuration
        grant: client_credentials # Or other appropriate grant
        client:
          id: workflow-oidc-client
          secret: ${ $secrets.oidcClientSecret }
        scopes: [ openid, profile, email ]
    # Certificate Auth (Conceptual - structure highly runtime dependent)
    # Runtimes might expect paths to cert/key files, or references to secrets
    # containing the certificate/key data.
    myCertAuth:
      certificate:
        # Example: Reference secrets containing PEM-encoded cert/key
        clientCertSecret: clientCertificatePem
        clientKeySecret: clientPrivateKeyPem
        # Example: Or maybe file paths accessible to the runtime
        # clientCertPath: /etc/certs/client.crt
        # clientKeyPath: /etc/certs/client.key
        # caCertPath: /etc/certs/ca.crt # Optional CA cert path
do:
  - getData:
      call: http
      with:
        method: get
        endpoint:
          uri: https://api.example.com/data
          # Reference the named authentication policy
          authentication: myApiClientOAuth
      then: processData
  - updateSystem:
      call: http
      with:
        method: post
        endpoint: https://internalsystem.example.com/update
        # Reference another named policy
        authentication: mySystemBasicAuth
        body:
        # ...
```

**Key Properties (`use.authentications`):**

* **`use.authentications`** (Map<String, Authentication>): A map where each key is a user-defined name for the
  authentication policy (e.g., `mySystemBasicAuth`) and the value is an `Authentication` object definition.

## Authentication Object Structure

The `Authentication` object defines the specific mechanism. Only *one* of the scheme-specific properties (`basic`,
`bearer`, `oauth2`, etc.) should be defined per object.

**Key Properties (`Authentication` Object):**

* **`use`** (String, Optional): *Used only in inline definitions*. References the name of a globally defined
  authentication policy from `use.authentications`. Cannot be used within `use.authentications` itself.
* **`basic`** (Object, Optional): Defines Basic Authentication. Contains:
    * `username` (String, Required): The username.
    * `password` (String, Required): The password (often uses a `${ $secrets... }` expression).
* **`bearer`** (Object, Optional): Defines Bearer Token Authentication. Contains:
    * `token` (String, Required): The bearer token value (often uses a `${ $secrets... }` or `${ $context... }`
      expression).
* **`oauth2`** (Object, Optional): Defines OAuth2 Authentication. Contains properties like:
    * `authority` (String, Required): The URI of the OAuth2 authorization server.
    * `grant` (String, Required): The grant type (e.g., `client_credentials`, `password`, `refresh_token`).
    * `client.id` (String): The client ID.
    * `client.secret` (String): The client secret.
    * `client.authentication` (String): Client auth method (e.g., `client_secret_basic`, `client_secret_post`).
    * `scopes` (List&lt;String>): List of requested scopes.
    * `audiences` (List&lt;String>): List of intended audiences.
    * (Other properties for different grants like `username`, `password`, `subject`, `actor`, specific endpoints - refer
      to detailed OAuth2 specifications if needed).
* **`oidc`** (Object, Optional): Defines OpenID Connect Authentication. Often relies on OIDC discovery via the
  `authority` URL. Contains properties like:
    * `authority` (String, Required): The URI of the OIDC provider (often pointing to the discovery document).
    * `grant` (String, Required): The grant type (e.g., `client_credentials`, `authorization_code`, `password`).
    * `client.id` (String): The client ID.
    * `client.secret` (String): The client secret.
    * `client.authentication` (String): Client auth method.
    * `scopes` (List&lt;String>): List of requested OIDC scopes (e.g., `openid`, `profile`, `email`).
    * `audiences` (List&lt;String>): List of intended audiences.
    * (Other grant-specific properties like `username`, `password`, `subject`, `actor`).
* **`certificate`** (Object, Optional): Defines Certificate-based Authentication (details depend on runtime support).
* **`digest`** (Object, Optional): Defines Digest Authentication. Contains:
    * `username` (String, Required): The username.
    * `password` (String, Required): The password.

*(Note: Support for `certificate` and the exact properties for `oauth2`/`oidc` may vary slightly depending on the
specific grant type and runtime implementation. Refer to the runtime documentation for precise details if deviating from
common flows like client credentials).*

## Inline Authentication

Authentication can also be defined directly where it's needed, typically within an `endpoint` object.

```yaml
do:
  - callServiceWithInlineAuth:
      call: http
      with:
        method: get
        endpoint:
          uri: https://onetime-service.example.com/resource
          # Define authentication directly here
          authentication:
            basic:
              username: temp_user
              password: ${ $secrets.tempPassword }
```

While convenient for simple cases, defining authentication inline is less reusable and can clutter task definitions.
Using the global `use.authentications` section is generally preferred. 