---
title: Resource Catalogs
---

# Resource Catalogs

A catalog groups reusable workflow resources. A workflow declares a catalog’s endpoint in `use.catalogs`
and can refer to a function by name, version, and catalog alias.

## Define a Catalog and Call Its Function

The shop serves a function that validates its `productId` argument and requests the matching product.
The call uses the form `name:version@catalog`.

<code-block lang="yaml" src="workflows/dsl-resource-catalog/catalog-read-product.yaml"/>

## Call a Function by URL

A function can also be referenced directly by its definition URL. Here the workflow points to the
same catalog entry without using an alias.

<code-block lang="yaml" src="workflows/dsl-resource-catalog/catalog-direct-url.yaml"/>

## Catalog Layout

Publish each function as a task definition at `functions/<name>/<version>/function.yaml` under the catalog
root. The version in the call selects the directory. The function body receives the caller’s `with` object.

> **Lemline**
>
> The worker resolves function references from HTTP, HTTPS, and local file catalog roots.
> References using the built-in `global` alias resolve against the public Open Workflow catalog.
> The following workflow declares a local `global` alias for repeatable runs without a remote catalog.

<code-block lang="yaml" src="workflows/dsl-resource-catalog/catalog-override-global.yaml"/>

## Separate Public and Private Catalogs

A workflow can declare more than one catalog. The second endpoint here uses an inline Basic policy.

<code-block lang="yaml" src="workflows/dsl-resource-catalog/catalog-authenticated-endpoint.yaml"/>

> **Lemline**
>
> Lemline accepts this workflow definition at registration. When it resolves the private function, it faults
> because catalog endpoint authentication is unsupported. That is separate from authentication on a
> service endpoint called by a function.

## Create a Local Catalog

The next workflow reads a file catalog on the worker. In the run section, create the complete
`function.yaml` that it calls. A function file contains a task definition, and the workflow below
provides the document metadata, input, and enclosing task list.

<code-block lang="yaml" src="workflows/dsl-resource-catalog/catalog-local-formatter.yaml"/>

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Use this command from the root of the source checkout to [start the tutorial shop](lemline-tutorial-setup.md#use-the-tutorial-shop).

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d --wait
```

Create the function file for the local catalog on the worker's machine.

```bash
mkdir -p /tmp/lemline-dsl-catalog/functions/format-product/1.0.0
cat > /tmp/lemline-dsl-catalog/functions/format-product/1.0.0/function.yaml <<'EOF'
set:
  label: '${ "Product " + .productId }'
EOF
```

Run these registration and start commands from the setup directory.

```bash
bin/lemline definition post -f catalog-read-product.yaml
bin/lemline instance start examples catalog-read-product --input '{"productId":"SKU-1"}'
bin/lemline definition post -f catalog-direct-url.yaml
bin/lemline instance start examples catalog-direct-url --input '{"productId":"SKU-2"}'
bin/lemline definition post -f catalog-override-global.yaml
bin/lemline instance start examples catalog-override-global --input '{"productId":"SKU-3"}'
bin/lemline definition post -f catalog-authenticated-endpoint.yaml
bin/lemline instance start examples catalog-authenticated-endpoint
bin/lemline definition post -f catalog-local-formatter.yaml
bin/lemline instance start examples catalog-local-formatter --input '{"productId":"SKU-2"}'
```

Check the individual results in the worker terminal.

| Example | Expected result |
|---|---|
| `catalog-read-product` | The product `SKU-1` (Garden hose). |
| `catalog-direct-url` | The product `SKU-2` (Watering can). |
| `catalog-override-global` | The product `SKU-3` (Pruning shears) from the shop catalog. |
| `catalog-authenticated-endpoint` | Registers, then faults on the private catalog’s endpoint authentication. |
| `catalog-local-formatter` | `{"label":"Product SKU-2"}` |

When the instances finish, use the command below in the source checkout to stop the shop.

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

## Related Resources

- [Function Calls](dsl-call-function.md)
- [Catalogs in the Open Workflow reference](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#catalog)
