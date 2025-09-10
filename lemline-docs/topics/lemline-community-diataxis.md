# Diátaxis Documentation Framework

This document explains how Lemline uses the Diátaxis documentation framework to organize our documentation. Understanding this framework will help you contribute effectively to our documentation and find what you need more efficiently.

## What is Diátaxis?

[Diátaxis](https://diataxis.fr/) is a systematic approach to technical documentation developed by Daniele Procida. It organizes documentation into four distinct categories based on user needs:

1. **Tutorials** - Learning-oriented content for beginners
2. **How-to Guides** - Problem-oriented guides for specific tasks
3. **Reference** - Information-oriented technical descriptions
4. **Explanation** - Understanding-oriented conceptual discussions

Each category serves a different purpose and addresses a different user need, requiring different approaches to writing and organization.

## The Four Documentation Types

### Tutorials: Learning-Oriented

**Purpose**: Help new users learn by doing

Tutorials are hands-on lessons that lead the user through a series of steps to complete a project or learn a concept. They are aimed at beginners who are just getting started.

**Characteristics**:
- Step-by-step instructions
- Focused on learning, not reference
- Build working examples
- Make success attainable
- Show instead of tell
- Reduce cognitive load

**Examples in Lemline Documentation**:
- [Hello World Tutorial](lemline-tutorial-hello.md)
- [Order Processing Tutorial](lemline-tutorial-order-processing.md)
- [IoT Device Monitoring Tutorial](lemline-tutorial-iot.md)

#### Tutorial Template

```markdown
# Tutorial: [Title]

This tutorial will guide you through [learning objective]. By the end, you'll have [concrete outcome].

## Prerequisites

Before starting, make sure you have:
- [Prerequisite 1]
- [Prerequisite 2]

## Step 1: [First Step]

1. [Action]
2. [Action]
3. [Action]

[Expected result]

## Step 2: [Second Step]

...

## What You've Learned

In this tutorial, you've learned:
- [Key learning 1]
- [Key learning 2]

## Next Steps

- Try [related tutorial]
- Learn about [related concept]
```

### How-to Guides: Problem-Oriented

**Purpose**: Help users accomplish specific tasks

How-to guides show users how to solve specific problems or accomplish particular tasks. They assume the user has basic knowledge and is trying to get something done.

**Characteristics**:
- Task-oriented
- Problem-focused
- Practical steps
- Flexible for different contexts
- Goal-driven
- Assume basic knowledge

**Examples in Lemline Documentation**:
- [How to Configure HTTP Timeouts](lemline-howto-timeouts.md)
- [How to Implement Retry Logic](lemline-howto-retry.md)
- [How to Use OAuth2 Authentication](lemline-howto-oauth2.md)

#### How-to Guide Template

```markdown
# How to [Accomplish Specific Task]

This guide explains how to [accomplish specific task] in Lemline.

## When to Use

Use this approach when:
- [Situation 1]
- [Situation 2]

## Prerequisites

- [Prerequisite 1]
- [Prerequisite 2]

## Steps

1. [Action with specific details]
2. [Action with specific details]
3. [Action with specific details]

## Example

```yaml
# Example configuration or code
```

## Common Issues and Solutions

- **Issue**: [Common problem]  
  **Solution**: [How to fix it]

## Related Information

- [Link to related how-to guide]
- [Link to relevant reference]
```

### Reference: Information-Oriented

**Purpose**: Provide technical details and specifications

Reference documentation describes the system's components, APIs, configurations, and commands in detail. It's factual, accurate, and complete.

**Characteristics**:
- Descriptive, not instructional
- Structured and consistent
- Comprehensive
- Accurate and precise
- Objective
- Doesn't explain concepts

**Examples in Lemline Documentation**:
- [CLI Reference](lemline-ref-cli.md)
- [DSL Syntax Reference](lemline-ref-dsl-syntax.md)
- [Configuration Options Reference](lemline-ref-config.md)

#### Reference Template

```markdown
# [Component/API/Feature] Reference

This reference documents [subject] in detail.

## Overview

[Brief description of the component/API/feature]

## Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `option1` | string | `"default"` | Description of option1 |
| `option2` | integer | `0` | Description of option2 |

## Methods/Commands

### `method1(param1, param2)`

**Parameters**:
- `param1` (type): Description
- `param2` (type): Description

**Returns**: (type) Description

**Example**:
```example
```

## Error Codes

| Code | Message | Description |
|------|---------|-------------|
| 1001 | "Error message" | Explanation |
```

### Explanation: Understanding-Oriented

**Purpose**: Help users understand concepts and principles

Explanations provide background knowledge, context, and conceptual information that helps users understand the "why" behind features and approaches.

**Characteristics**:
- Conceptual understanding
- Provides context
- Explains alternatives
- Discusses trade-offs
- Theoretical rather than practical
- Builds mental models

**Examples in Lemline Documentation**:
- [Understanding the Workflow Execution Model](lemline-explain-execution.md)
- [Understanding Error Handling in Lemline](lemline-explain-errors.md)
- [Understanding JQ Expressions](lemline-explain-jq.md)

#### Explanation Template

```markdown
# Understanding [Concept]

This document explains [concept] in Lemline and why it's important.

## What is [Concept]?

[Clear, concise definition]

## Why [Concept] Matters

[Explanation of importance]

## Key Principles

### Principle 1: [Name]

[Explanation of the principle]

### Principle 2: [Name]

[Explanation of the principle]

## How [Concept] Works in Lemline

[Detailed explanation with diagrams if helpful]

## Common Misconceptions

[Address any frequent misunderstandings]

## Relationship to Other Concepts

[How this concept relates to other important concepts]

## Further Reading

- [Link to related explanation]
- [Link to external resource]
```

## Applying Diátaxis in Lemline Documentation

### Content Organization

Lemline documentation is organized following the Diátaxis model:

1. **Learning Path for Newcomers**
   - Getting started
   - Tutorials section
   - Conceptual overviews (explanations)

2. **Problem-Solving for Practitioners**
   - How-to guides categorized by task type
   - Real-world examples
   - Troubleshooting guides

3. **Technical Details for Implementers**
   - API reference
   - DSL reference
   - Configuration options
   - CLI commands

4. **Conceptual Knowledge for Understanding**
   - Architecture explanations
   - Design principles
   - Decision rationales

### File Naming Conventions

To clearly identify the type of documentation, we use these prefixes:

- `lemline-tutorial-` for tutorials
- `lemline-howto-` for how-to guides
- `lemline-ref-` for reference documentation
- `lemline-explain-` for explanations
- `lemline-example-` for example libraries

### Cross-Referencing Between Types

Documentation should cross-reference between the four types:

- Tutorials link to relevant how-to guides for further tasks
- How-to guides reference the detailed API documentation
- Reference documentation links to explanations for context
- Explanations link to tutorials and how-to guides for practical applications

## Writing Guidelines by Documentation Type

### Tutorial Writing Guidelines

- Write for complete beginners to the topic
- Focus on concrete, working examples
- Use a conversational, encouraging tone
- Break down steps into manageable chunks
- Anticipate and prevent errors
- Keep focused on the learning path
- Test all tutorials thoroughly

### How-to Guide Writing Guidelines

- Focus on specific tasks or problems
- Provide clear, concise steps
- Assume basic knowledge
- Provide working examples
- Offer troubleshooting tips
- Make steps adaptable to different scenarios
- Explain why certain steps are taken

### Reference Writing Guidelines

- Be comprehensive and accurate
- Use consistent structure and formatting
- Provide all necessary technical details
- Be objective and factual
- Document all options and behaviors
- Use precise technical language
- Include complete examples

### Explanation Writing Guidelines

- Focus on building understanding
- Explain why, not just how
- Discuss design decisions and trade-offs
- Use analogies and diagrams to clarify concepts
- Make connections between related concepts
- Provide context and background
- Anticipate conceptual challenges

## Evaluating Documentation Quality

For each documentation type, ask yourself:

### Tutorial Quality Checklist

- Can a complete beginner successfully follow it?
- Does it build a working example?
- Does it explain what the user is learning and why?
- Are the steps clear and manageable?
- Does it lead to a sense of accomplishment?

### How-to Guide Quality Checklist

- Does it address a specific task or problem?
- Are the steps clear and practical?
- Can it be adapted to various scenarios?
- Does it provide troubleshooting advice?
- Is it focused on practical outcomes?

### Reference Quality Checklist

- Is it accurate and complete?
- Is it structured consistently?
- Does it document all available options?
- Is it precise and technically correct?
- Is it easy to navigate and search?

### Explanation Quality Checklist

- Does it build conceptual understanding?
- Does it explain why, not just how?
- Does it discuss alternatives and trade-offs?
- Does it provide helpful context?
- Does it correct common misconceptions?

## Best Practices for Contributors

When contributing to Lemline documentation:

1. **Identify the Documentation Type** - Determine which of the four types best matches your contribution
2. **Follow the Templates** - Use the templates provided for each document type
3. **Maintain Separation** - Keep different documentation types separate; don't mix tutorials with reference material
4. **Cross-Reference** - Link between document types to create a cohesive documentation system
5. **Use Appropriate Style** - Adapt your writing style to the documentation type
6. **Test and Review** - Ensure your documentation works as intended for its type

## Conclusion

The Diátaxis framework provides a clear structure for organizing technical documentation based on user needs. By separating tutorials, how-to guides, reference, and explanation, we create a more usable and effective documentation system for Lemline users at all levels of experience.

Understanding these documentation types will help you navigate the existing documentation more effectively and contribute new documentation that serves its intended purpose.

---

For more information, see:
- [Official Diátaxis Website](https://diataxis.fr/)
- [Contribution Guidelines](lemline-community-contribution.md)
- [Documentation Style Guide](lemline-community-style.md)