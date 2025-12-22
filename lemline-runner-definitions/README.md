# lemline-runner-definitions

> Workflow definition storage and management

## Purpose

This module handles persistent storage of workflow definitions in the database:
- **CRUD operations** for workflow definitions (namespace/name/version)
- **Cache synchronization** with the in-memory `WorkflowCache`
- **Listen task extraction** for CloudEvent-triggered workflows

## Serverless Workflow DSL Reference

Workflow definitions follow the [Serverless Workflow DSL v1.0](https://serverlessworkflow.io/):
- `document.namespace` - Workflow namespace
- `document.name` - Workflow name
- `document.version` - Workflow version (semver)

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                   lemline-runner-definitions                    │
├─────────────────────────────────────────────────────────────────┤
│  DefinitionService          ← Business logic for CRUD          │
│  ├── save()                 ← Create/update with cache sync    │
│  ├── findByNameAndVersion() ← Query by identity                │
│  ├── delete()               ← Delete with listener cleanup     │
│  └── listByName()           ← List all versions                │
│                                                                 │
│  DefinitionModel            ← Database entity                  │
│  ├── namespace              ← Workflow namespace               │
│  ├── name                   ← Workflow name                    │
│  ├── version                ← Workflow version                 │
│  └── definition             ← YAML/JSON content                │
│                                                                 │
│  DefinitionRepository       ← Database operations              │
│                                                                 │
│  DefinitionListenService    ← Extract listen tasks from defs   │
│                                                                 │
│  DefinitionCacheSync        ← Sync DB → WorkflowCache on boot  │
│                                                                 │
│  Starter                    ← Prepare workflow for execution   │
└─────────────────────────────────────────────────────────────────┘
```

## Key Concepts

| Concept | Description |
|---------|-------------|
| **Workflow Identity** | Unique tuple of (namespace, name, version) |
| **WorkflowCache** | In-memory cache of parsed workflow definitions for fast access |
| **DefinitionCacheSync** | Startup observer that loads all definitions from DB into cache |
| **Listen Tasks** | Tasks that wait for CloudEvents - extracted and registered for matching |

## File Reference

| File | Responsibility |
|------|----------------|
| `DefinitionService.kt` | Business logic coordinating repository, cache, and listeners |
| `DefinitionModel.kt` | Data class representing a stored workflow definition |
| `DefinitionRepository.kt` | Database operations for definitions table |
| `DefinitionListenService.kt` | Extract and register listen task configurations |
| `DefinitionCacheSync.kt` | Load definitions into cache on application startup |
| `DefinitionConfig.kt` | Configuration for definition-related settings |
| `Definitions.kt` | Utility functions for working with definitions |
| `Starter.kt` | Prepare workflow instances for execution |

## How It Works

### Definition Save Flow

1. **Parse** - YAML/JSON parsed via `WorkflowCache.parseYaml()`
2. **Extract Identity** - Namespace, name, version from workflow document
3. **Persist** - Insert or update in `lemline_definitions` table
4. **Cache** - Add parsed workflow to `WorkflowCache`
5. **Register Listeners** - Extract listen tasks for CloudEvent matching

### Cache Synchronization

```
┌──────────────────┐    startup    ┌──────────────────┐
│   Database       │ ───────────▶  │   WorkflowCache  │
│  (definitions)   │               │   (in-memory)    │
└──────────────────┘               └──────────────────┘
        │                                   ▲
        │         DefinitionCacheSync       │
        └───────────────────────────────────┘
```

## Dependencies

| Depends On | Used By |
|------------|---------|
| `lemline-runner-common` | `lemline-runner` |
| `lemline-core` (WorkflowCache) | `lemline-runner-cli` |

## Extension Points

| Extension Point | How to Extend |
|-----------------|---------------|
| **Definition validation** | Add validation in `DefinitionService.save()` |
| **Custom storage** | Extend `DefinitionRepository` |
| **Post-save hooks** | Add observers in `DefinitionService` |

## Database Table

### `lemline_definitions`

| Column | Type | Description |
|--------|------|-------------|
| `namespace` | VARCHAR(255) | Workflow namespace (PK part) |
| `name` | VARCHAR(255) | Workflow name (PK part) |
| `version` | VARCHAR(255) | Workflow version (PK part) |
| `definition` | TEXT | Complete YAML/JSON workflow content |
| `created_at` | TIMESTAMP | Record creation time |
| `updated_at` | TIMESTAMP | Last update time |
