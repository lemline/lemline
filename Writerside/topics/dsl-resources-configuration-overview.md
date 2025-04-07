---
title: Resources & Configuration Overview
---

# Resources & Configuration Overview

## Introduction

Beyond the core workflow logic and task execution, the Serverless Workflow DSL provides mechanisms for managing reusable resources, defining specific syntaxes, and configuring cross-cutting concerns like security and timing. This section covers these essential aspects that help you build more robust, maintainable, and secure workflows.

Understanding these features allows you to effectively manage external dependencies, authentication details, and global settings within your workflow definitions.

## Topics Covered

This section delves into the following key areas:

*   **[Resource Catalog](dsl-resource-catalog.md):** Explains how to define and reference reusable components like function definitions, authentication details, event definitions, and more. This promotes modularity and avoids repetition within your workflow files.
*   **[URI Templates](dsl-uri-templates.md):** Describes the syntax for URI templates (RFC 6570) used in certain configuration fields (like error types or potentially within API calls), allowing for standardized and potentially dynamic URI construction.
*   **[Timeouts](dsl-timeouts.md):** Covers how to configure various timeouts within your workflow, including overall workflow duration, task execution limits, and event listening periods, ensuring workflows don't run indefinitely or get stuck.
*   **[Authentication](dsl-authentication.md):** Details how to define and reference authentication configurations (like Basic Auth, OAuth2, Bearer Tokens) needed to securely interact with external services called by your workflow tasks.

These configuration and resource management capabilities are vital for integrating workflows with real-world systems and ensuring they operate securely and efficiently. 