# Serialization Reference

This reference documents all aspects of serialization in Lemline, including formats, configuration, and customization options.

## Serialization Overview

Lemline uses serialization for various purposes:

1. **Workflow Definitions**: Parsing YAML/JSON workflow definitions
2. **Data Transformation**: Converting between formats during workflow execution
3. **Message Handling**: Serializing/deserializing messages for messaging systems
4. **Persistence**: Storing workflow state and definitions in databases
5. **API Communication**: Request/response serialization for API calls

## Supported Formats

### JSON

JSON (JavaScript Object Notation) is the primary serialization format:

```json
{
  "name": "John Doe",
  "age": 30,
  "address": {
    "street": "123 Main St",
    "city": "Anytown",
    "zip": "12345"
  },
  "phoneNumbers": [
    {
      "type": "home",
      "number": "555-1234"
    },
    {
      "type": "work",
      "number": "555-5678"
    }
  ]
}
```

### YAML

YAML is supported for workflow definitions:

```yaml
name: John Doe
age: 30
address:
  street: 123 Main St
  city: Anytown
  zip: "12345"
phoneNumbers:
  - type: home
    number: 555-1234
  - type: work
    number: 555-5678
```

### XML

XML is supported for interoperability with XML-based services:

```xml
<person>
  <name>John Doe</name>
  <age>30</age>
  <address>
    <street>123 Main St</street>
    <city>Anytown</city>
    <zip>12345</zip>
  </address>
  <phoneNumbers>
    <phoneNumber>
      <type>home</type>
      <number>555-1234</number>
    </phoneNumber>
    <phoneNumber>
      <type>work</type>
      <number>555-5678</number>
    </phoneNumber>
  </phoneNumbers>
</person>
```

### Protocol Buffers

Protocol Buffers (protobuf) are supported for gRPC communication:

```protobuf
syntax = "proto3";

message Person {
  string name = 1;
  int32 age = 2;
  Address address = 3;
  repeated PhoneNumber phone_numbers = 4;
}

message Address {
  string street = 1;
  string city = 2;
  string zip = 3;
}

message PhoneNumber {
  string type = 1;
  string number = 2;
}
```

### Avro

Avro is supported for schema-based serialization with Kafka:

```json
{
  "type": "record",
  "name": "Person",
  "fields": [
    {"name": "name", "type": "string"},
    {"name": "age", "type": "int"},
    {
      "name": "address",
      "type": {
        "type": "record",
        "name": "Address",
        "fields": [
          {"name": "street", "type": "string"},
          {"name": "city", "type": "string"},
          {"name": "zip", "type": "string"}
        ]
      }
    },
    {
      "name": "phoneNumbers",
      "type": {
        "type": "array",
        "items": {
          "type": "record",
          "name": "PhoneNumber",
          "fields": [
            {"name": "type", "type": "string"},
            {"name": "number", "type": "string"}
          ]
        }
      }
    }
  ]
}
```

### CBOR

CBOR (Concise Binary Object Representation) is supported for efficient binary serialization.

### MessagePack

MessagePack is supported for compact binary serialization.

## JSON Serialization

### JSON Features

Lemline's JSON support includes:

- **Full JSON specification compliance**
- **JSON Schema validation**
- **JSONPath for data extraction**
- **JQ for data transformation**
- **JSON Pointer for precise addressing**

### JSON Configuration

Configure JSON handling:

```properties
# Serialization settings
lemline.serialization.json.pretty-print=false
lemline.serialization.json.escape-non-ascii=false
lemline.serialization.json.write-dates-as-timestamps=false
lemline.serialization.json.fail-on-unknown-properties=false
lemline.serialization.json.default-value-inclusion=NON_NULL
```

### JSON Schema

Validate against JSON Schema:

```yaml
input:
  schema:
    type: "object"
    required: ["name", "age"]
    properties:
      name:
        type: "string"
      age:
        type: "integer"
        minimum: 0
      address:
        type: "object"
        properties:
          street:
            type: "string"
          city:
            type: "string"
          zip:
            type: "string"
            pattern: "^[0-9]{5}$"
```

## Data Type Handling

### Numeric Types

```json
{
  "intValue": 42,
  "longValue": 9223372036854775807,
  "floatValue": 3.14,
  "doubleValue": 3.141592653589793
}
```

### String Types

```json
{
  "string": "Hello, World!",
  "multiline": "Line 1\nLine 2",
  "specialChars": "Special characters: é, ñ, ç"
}
```

### Boolean Types

```json
{
  "trueValue": true,
  "falseValue": false
}
```

### Null Values

```json
{
  "nullValue": null
}
```

### Array Types

```json
{
  "emptyArray": [],
  "numberArray": [1, 2, 3, 4, 5],
  "stringArray": ["apple", "banana", "cherry"],
  "mixedArray": [1, "text", true, null],
  "objectArray": [
    {"name": "John", "age": 30},
    {"name": "Jane", "age": 25}
  ]
}
```

### Object Types

```json
{
  "emptyObject": {},
  "nestedObject": {
    "property1": "value1",
    "property2": {
      "nestedProperty": "nestedValue"
    }
  }
}
```

### Date/Time Types

```json
{
  "date": "2023-05-15",
  "time": "14:30:15",
  "dateTime": "2023-05-15T14:30:15Z",
  "dateTimeWithOffset": "2023-05-15T14:30:15+02:00",
  "duration": "PT1H30M"
}
```

ISO 8601 format is used for date/time values.

### Binary Data

Binary data is encoded in Base64:

```json
{
  "binaryData": "SGVsbG8sIFdvcmxkIQ=="
}
```

### Custom Types

Custom types can be handled through serializers/deserializers:

```json
{
  "uuid": "123e4567-e89b-12d3-a456-426614174000",
  "money": {
    "amount": 100.50,
    "currency": "USD"
  }
}
```

## Serialization Customization

### Custom Serializers

Custom serializers can be registered for specific types:

```yaml
use:
  serializers:
    - name: "moneySerializer"
      class: "com.example.MoneySerializer"
      target: "com.example.Money"
```

### Format Modifiers

Format modifiers can be applied to values:

```yaml
set:
  formattedDate: "${ .date | format('yyyy-MM-dd') }"
  formattedNumber: "${ .number | format('#,###.00') }"
  formattedCurrency: "${ .amount | format('$#,###.00') }"
```

### Serialization Contexts

Different serialization contexts can be used for different purposes:

```yaml
extension:
  serialization:
    context: "persistence"  # Options: default, api, persistence, messaging
```

## Messaging Protocol Serialization

### Kafka Serialization

Configure Kafka serialization:

```properties
# Kafka serialization
lemline.messaging.kafka.key-serializer=org.apache.kafka.common.serialization.StringSerializer
lemline.messaging.kafka.value-serializer=io.confluent.kafka.serializers.KafkaAvroSerializer
lemline.messaging.kafka.schema-registry-url=http://schema-registry:8081
```

### RabbitMQ Serialization

Configure RabbitMQ serialization:

```properties
# RabbitMQ serialization
lemline.messaging.rabbitmq.content-type=application/json
lemline.messaging.rabbitmq.message-converter=org.springframework.amqp.support.converter.Jackson2JsonMessageConverter
```

## HTTP Content Type Handling

### Request Content Types

Specify content types for HTTP requests:

```yaml
callHTTP:
  url: "https://api.example.com/users"
  method: "POST"
  headers:
    Content-Type: "application/json"
  body: "${ .user }"
```

Supported request content types:
- `application/json`
- `application/xml`
- `text/plain`
- `application/x-www-form-urlencoded`
- `multipart/form-data`
- `application/octet-stream`

### Response Content Types

Handle different response content types:

```yaml
callHTTP:
  url: "https://api.example.com/data"
  method: "GET"
  headers:
    Accept: "application/json, application/xml;q=0.9"
```

## Schema Registry Integration

### Schema Registry Config

Configure schema registry:

```properties
lemline.schema-registry.url=http://schema-registry:8081
lemline.schema-registry.cache.size=100
lemline.schema-registry.cache.ttl=PT1H
```

### Schema Registry Usage

Use schema registry for Avro serialization:

```yaml
callAsyncAPI:
  api: "kafka-service"
  publish:
    channel: "orders"
    schemaRegistry:
      url: "http://schema-registry:8081"
      subject: "orders-value"
      strategy: "TopicNameStrategy"
    message:
      payload: "${ .order }"
```

## Type Conversions

### Automatic Type Conversions

Lemline automatically converts between compatible types:

| Source Type | Target Type | Notes |
|-------------|-------------|-------|
| String | Number | If string contains valid number |
| Number | String | Converts number to string representation |
| String | Boolean | "true"/"false" strings |
| String | Date/Time | If string is in ISO 8601 format |
| Array | String | Joins array elements with commas |
| Object | Map | Converts JSON object to Map |
| String | UUID | If string is valid UUID format |
| String | URI | If string is valid URI format |
| Binary | String | Base64-encodes binary data |

### Explicit Type Conversions

Explicit conversions using JQ functions:

```yaml
set:
  intValue: "${ number(.stringValue) }"
  stringValue: "${ string(.intValue) }"
  boolValue: "${ boolean(.stringValue) }"
  dateValue: "${ fromdateiso8601(.stringValue) }"
  base64Value: "${ base64encode(.binaryValue) }"
```

## Serialization Formats

### JSON Formats

```yaml
extension:
  serialization:
    json:
      dateFormat: "yyyy-MM-dd'T'HH:mm:ss.SSSZ"
      timeZone: "UTC"
      prettyPrint: false
      escapeNonAscii: false
      writeDatesAsTimestamps: false
      writeNullMapValues: false
      writeEmptyArrays: true
```

### XML Formats

```yaml
extension:
  serialization:
    xml:
      dateFormat: "yyyy-MM-dd'T'HH:mm:ss.SSSZ"
      timeZone: "UTC"
      prettyPrint: false
      xmlDeclaration: true
      rootName: "root"
      defaultNamespace: "http://example.com/schema"
```

### Avro Formats

```yaml
extension:
  serialization:
    avro:
      schemaRegistry: "http://schema-registry:8081"
      specificRecordBase: true
      reflectionAllowed: false
```

## Serialization Performance

### Serialization Caching

Enable serialization caching:

```properties
lemline.serialization.cache.enabled=true
lemline.serialization.cache.max-entries=1000
lemline.serialization.cache.ttl=PT1H
```

### Serialization Benchmarks

Approximate serialization performance for 1KB object:

| Format | Serialization Time | Deserialization Time | Size | Notes |
|--------|-------------------|---------------------|------|-------|
| JSON | 0.01 ms | 0.02 ms | 1.0x | Human-readable |
| YAML | 0.05 ms | 0.08 ms | 0.9x | Human-readable |
| XML | 0.03 ms | 0.04 ms | 1.5x | Human-readable |
| Protocol Buffers | 0.005 ms | 0.006 ms | 0.6x | Binary |
| Avro | 0.008 ms | 0.01 ms | 0.7x | Binary |
| CBOR | 0.007 ms | 0.009 ms | 0.8x | Binary |
| MessagePack | 0.006 ms | 0.008 ms | 0.75x | Binary |

## Serialization Security

### Input Validation

Always validate input:

```yaml
input:
  schema:
    # JSON Schema definition
  validate: true
```

### Deserialization Vulnerabilities

Mitigate deserialization vulnerabilities:

```properties
lemline.serialization.json.enable-default-typing=false
lemline.serialization.json.block-unsafe-types=true
lemline.serialization.json.filter.enabled=true
lemline.serialization.json.filter.packages-allowed=com.lemline
```

### Sensitive Data Handling

Handle sensitive data properly:

```yaml
extension:
  serialization:
    sensitive-fields:
      - "password"
      - "creditCard"
      - "ssn"
    sensitive-field-handling: "mask"  # Options: mask, remove, encrypt
```

## Error Handling

### Serialization Errors

Handle serialization errors:

```yaml
- serializeData:
    try:
      do:
        - transform:
            set:
              serializedData: "${ .complexObject }"
      catch:
        - error:
            with:
              type: "https://serverlessworkflow.io/spec/1.0.0/errors/expression"
            as: "serializationError"
          do:
            - handleError:
                set:
                  errorMessage: "Failed to serialize data: ${ .serializationError.details }"
```

### Schema Validation Errors

Handle schema validation errors:

```yaml
- validateData:
    try:
      do:
        - checkSchema:
            extension:
              schemaValidator:
                schema: "${ .userSchema }"
                instance: "${ .userData }"
      catch:
        - error:
            with:
              type: "https://serverlessworkflow.io/spec/1.0.0/errors/validation"
            as: "validationError"
          do:
            - handleValidationError:
                set:
                  errorDetails: "${ .validationError.details }"
```

## Serialization Examples

### Complex Object Serialization

```yaml
- serializeOrder:
    set:
      orderJson: "${ toJson(.order) }"
      orderXml: "${ toXml(.order) }"
      orderAvro: "${ toAvro(.order, 'com.example.Order') }"
```

### Custom Format Serialization

```yaml
- serializeCustomFormat:
    extension:
      serializer:
        name: "CSV"
        input: "${ .records }"
        options:
          delimiter: ","
          header: true
          columns: ["id", "name", "email"]
```

### Cross-Format Conversion

```yaml
- convertFormat:
    extension:
      converter:
        from: "XML"
        to: "JSON"
        input: "${ .xmlData }"
        options:
          rootName: "root"
```

## Related Resources

- [JQ Expressions Guide](lemline-howto-jq.md)
- [Schema Validation](lemline-howto-schemas.md)
- [HTTP Protocol Reference](lemline-ref-http.md)
- [AsyncAPI Reference](lemline-ref-asyncapi.md)
- [gRPC Protocol Reference](lemline-ref-grpc.md)