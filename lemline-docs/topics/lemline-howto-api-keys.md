# Using API Keys for Authentication

Declare the key under `use.secrets` and configure its value on the worker. Place the key where the API expects it. This workflow sends it in the `X-API-Key` header.

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: fetch-protected-data
  version: '1.0.0'
use:
  secrets: [apiKey]
do:
  - fetchData:
      call: http
      with:
        method: get
        endpoint: https://api.example.com/data
        headers:
          X-API-Key: ${ $secrets.apiKey }
```

## Query parameters

For an API that requires an `api_key` query parameter, use the following task in the same workflow. Query strings can appear in server and proxy logs.

```yaml
- fetchData:
    call: http
    with:
      method: get
      endpoint: https://api.example.com/data
      query:
        api_key: ${ $secrets.apiKey }
```

## Custom headers

An API can also require a prefix or a separate application identifier. Declare both secret names and set the headers on the request.

```yaml
use:
  secrets: [apiKey, appId]
do:
  - fetchData:
      call: http
      with:
        method: get
        endpoint: https://api.example.com/data
        headers:
          Authorization: '${ "ApiKey " + $secrets.apiKey }'
          X-App-ID: ${ $secrets.appId }
```

For an `Authorization: Bearer` header, use a named Bearer policy. To deploy the workflow to staging and production, bind the same secret name to the corresponding value on each worker. Verify the expected header name and prefix when troubleshooting a 401 or 403 response.

See [Authentication Reference](lemline-ref-auth.md) and [Secrets](dsl-secrets.md).
