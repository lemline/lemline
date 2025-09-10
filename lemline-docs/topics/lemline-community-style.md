# Documentation Style Guide

This style guide outlines the standards and conventions for writing documentation for the Lemline project. Following these guidelines ensures that our documentation remains consistent, clear, and accessible to all users.

## Table of Contents

- [Documentation Principles](#documentation-principles)
- [Documentation Structure](#documentation-structure)
- [Writing Style](#writing-style)
- [Formatting and Syntax](#formatting-and-syntax)
- [Code Examples](#code-examples)
- [Images and Diagrams](#images-and-diagrams)
- [Terminology and Glossary](#terminology-and-glossary)
- [Documentation Testing](#documentation-testing)
- [Localization Guidelines](#localization-guidelines)
- [SEO Considerations](#seo-considerations)

## Documentation Principles

Our documentation follows these core principles:

1. **User-Centered** - Focus on what users need to know, not what's easy to document
2. **Clear and Concise** - Use simple language and avoid unnecessary words
3. **Comprehensive** - Cover all features and use cases, but without overwhelming detail
4. **Maintainable** - Structure documentation for easy updates
5. **Accessible** - Ensure documentation is accessible to users with different abilities
6. **Example-Rich** - Provide practical, realistic examples

## Documentation Structure

Lemline documentation follows the [Diátaxis framework](https://diataxis.fr/), organizing content into four types:

1. **Tutorials** - Learning-oriented lessons that guide users through a series of steps
2. **How-to Guides** - Problem-oriented guides that show how to solve specific problems
3. **Reference** - Information-oriented technical descriptions of the system
4. **Explanation** - Understanding-oriented discussions of concepts

For more details on this structure, see our [Diátaxis Guide](lemline-community-diataxis.md).

### File Organization

Documentation files should follow this organization:

- File names should use kebab-case: `file-name.md`
- Use a prefix to indicate the document type:
  - `lemline-tutorial-` for tutorials
  - `lemline-howto-` for how-to guides
  - `lemline-ref-` for reference documentation
  - `lemline-explain-` for explanations
  - `lemline-example-` for examples
- Supporting files (images, etc.) should use the same naming conventions

### Document Structure

Each document should include:

1. **Title** - Clear, descriptive H1 title
2. **Introduction** - Brief overview of what the document covers
3. **Main Content** - Organized with clear headings
4. **Conclusion** - Summary of key points (when appropriate)
5. **Related Resources** - Links to related documentation

## Writing Style

### Voice and Tone

- Use a friendly, professional tone
- Write in a conversational style, but avoid slang
- Address the reader directly using "you"
- Use active voice instead of passive voice
- Be positive and supportive, not condescending

### Language Guidelines

- Use simple, clear language
- Avoid jargon when possible; when necessary, explain it
- Use consistent terminology throughout all documentation
- Write in present tense when possible
- Use gender-neutral language

### Examples:

Instead of: "The configuration file must be created by the user."  
Write: "Create a configuration file."

Instead of: "It is recommended that you back up your data."  
Write: "Back up your data."

## Formatting and Syntax

### Markdown Usage

Lemline documentation uses Markdown with these conventions:

#### Headers

- Use ATX-style headers with a space after the hash signs: `## Heading`
- Use sentence case for headings: "Creating a new workflow" not "Creating a New Workflow"
- Maintain hierarchical structure (H1 > H2 > H3)
- Don't skip levels (e.g., don't go from H2 to H4)

```markdown
# Main Title (H1) - Only one per document

## Section Title (H2)

### Subsection Title (H3)

#### Detailed Topic (H4)
```

#### Lists

- Use unordered lists for items without sequence
- Use ordered lists for sequential steps
- Indent sublists with 2 spaces
- End each list item with a period if it's a complete sentence

```markdown
- This is an unordered list item.
- This is another item.
  - This is a subitem.
  - This is another subitem.

1. First step in the sequence.
2. Second step in the sequence.
   1. First substep.
   2. Second substep.
```

#### Text Formatting

- Use **bold** for emphasis: `**bold**`
- Use *italics* for definitions or to introduce new terms: `*italics*`
- Use `code formatting` for code, file names, and technical terms: `` `code` ``
- Use blockquotes for notes or quotes: `> Note: This is important.`

#### Links

- Use descriptive link text: [Creating a workflow](workflow-creation.md) instead of [click here](workflow-creation.md)
- For external links, indicate when they open outside the documentation
- Use relative links for internal documentation

```markdown
[Descriptive link text](relative/path/to/document.md)
[External service](https://example.com) (external link)
```

#### Tables

- Use tables for structured data
- Include header row and column separators
- Align columns for readability

```markdown
| Name | Type | Description |
|------|------|-------------|
| id | string | Unique identifier |
| status | enum | Current status |
```

## Code Examples

### Code Blocks

- Always specify the language for syntax highlighting
- Use fenced code blocks with three backticks
- Keep examples concise and focused
- Include comments where necessary

```markdown
```yaml
# This is a YAML example
document:
  dsl: '1.0.0'
  namespace: examples
  name: hello-world
  version: '0.1.0'
do:
  - greet:
      set:
        message: "Hello, world!"
```
```

### Command Line Examples

- Include the command prompt symbol (`$` for regular user, `#` for root)
- Separate command from output
- Use backslashes for line continuation

```markdown
```bash
$ lemline instance start -n examples.hello-world -v 0.1.0
Instance started: 1234-5678-90ab-cdef
```
```

### Example Best Practices

- Provide complete, working examples
- Use realistic data and scenarios
- Show both simple cases and advanced usage
- Include error handling in examples
- Test all examples to ensure they work as documented

## Images and Diagrams

### Image Guidelines

- Use images to clarify complex concepts, not to replace text
- Include alt text for all images
- Keep images at reasonable dimensions (max width 800px)
- Use SVG for diagrams when possible
- Use consistent styling across all diagrams

### Image Formatting

```markdown
![Alt text describing the image](images/diagram-name.png)
```

### Diagram Types

- **Workflow Diagrams** - Show the flow of execution
- **Architecture Diagrams** - Show system components and their relationships
- **Concept Diagrams** - Illustrate abstract concepts
- **Screenshots** - Show UI elements (when applicable)

## Terminology and Glossary

### Consistent Terminology

Maintain consistency with these key terms:

- **Workflow** - A complete definition of a process
- **Instance** - A single execution of a workflow
- **Node** - An individual step within a workflow
- **Token** - A marker representing execution position
- **Task** - A unit of work within a workflow

For a complete list, refer to the [Lemline Glossary](glossary.md).

### Abbreviations and Acronyms

- Define abbreviations and acronyms on first use
- Include the full term followed by the abbreviation in parentheses
- For commonly known abbreviations (HTTP, API), definition is not necessary

Example: "The JavaScript Object Notation (JSON) format is used for configuration."

## Documentation Testing

### Review Checklist

Before submitting documentation, check for:

1. **Technical accuracy** - All information is correct
2. **Completeness** - All necessary information is included
3. **Clarity** - Information is easy to understand
4. **Consistency** - Style and terminology are consistent
5. **Links** - All links work correctly
6. **Code examples** - All code examples work as expected
7. **Spelling and grammar** - No errors

### Testing Process

1. Run the documentation locally with:
   ```bash
   ./gradlew :lemline-docs:runDocSite
   ```
2. Review the generated site at http://localhost:8080
3. Verify all links work correctly
4. Test all code examples to ensure they function as expected

## Localization Guidelines

### Writing for Translation

- Use simple sentence structures
- Avoid idioms, colloquialisms, and culture-specific references
- Provide context for translators where necessary
- Keep sentences relatively short (< 25 words when possible)

### Localized Examples

- For localized documentation, ensure examples work in the target language
- Consider cultural differences when creating examples
- Avoid culturally specific references

## SEO Considerations

To improve documentation discoverability:

- Use descriptive, keyword-rich titles
- Include relevant keywords naturally in the content
- Create descriptive URLs
- Use semantic HTML elements (headings, lists, etc.)
- Include descriptive alt text for images
- Create a comprehensive table of contents

## Before You Submit

Before submitting documentation, ask yourself:

1. Would this make sense to someone new to Lemline?
2. Have I provided all necessary context?
3. Are there clear examples?
4. Have I eliminated all unnecessary words and explanations?
5. Is the formatting consistent with our guidelines?
6. Have I tested all examples and verified all links?

## Additional Resources

- [Google Developer Documentation Style Guide](https://developers.google.com/style)
- [Microsoft Style Guide](https://docs.microsoft.com/style-guide/welcome/)
- [Diátaxis Documentation Framework](https://diataxis.fr/)
- [The Documentation System](https://documentation.divio.com/)

---

For more information on Lemline documentation, see:
- [Contribution Guidelines](lemline-community-contribution.md)
- [Diátaxis Documentation Framework](lemline-community-diataxis.md)