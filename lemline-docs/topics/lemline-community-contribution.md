# Contributing to Lemline

This document explains how to contribute to the Lemline project. Whether you're interested in fixing bugs, adding features, improving documentation, or helping with community support, your contributions are welcome and appreciated.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Workflow](#development-workflow)
- [Contribution Types](#contribution-types)
- [Pull Request Process](#pull-request-process)
- [Documentation Contributions](#documentation-contributions)
- [Community Support](#community-support)
- [Becoming a Core Contributor](#becoming-a-core-contributor)

## Code of Conduct

The Lemline project is committed to fostering an open and welcoming environment. All participants in our project and community are expected to adhere to our Code of Conduct:

- Treat everyone with respect and kindness
- Be thoughtful in your communications
- Be welcoming and inclusive
- Be considerate
- Give and gracefully accept constructive feedback
- Respect the privacy and safety of others

For the full Code of Conduct, please see [CODE_OF_CONDUCT.md](https://github.com/lemline/lemline/blob/main/CODE_OF_CONDUCT.md).

## Getting Started

### Prerequisites

Before you start contributing, you'll need:

1. **Java Development Kit (JDK)** - Version 17 or higher
2. **Gradle** - The project uses the Gradle wrapper, so you don't need to install it separately
3. **Git** - For version control
4. **Docker** - For running integration tests with containerized dependencies
5. **IDE** - IntelliJ IDEA is recommended, but any IDE with Kotlin support will work

### Setting Up Your Development Environment

1. Fork the repository on GitHub
2. Clone your fork locally:
   ```bash
   git clone https://github.com/<your-username>/lemline.git
   cd lemline
   ```

3. Set up the upstream remote:
   ```bash
   git remote add upstream https://github.com/lemline/lemline.git
   ```

4. Build the project:
   ```bash
   ./gradlew build
   ```

5. Run tests:
   ```bash
   ./gradlew test
   ```

### Project Structure Overview

Lemline follows a modular architecture:

- **lemline-core** - Core execution engine and workflow model
- **lemline-runner** - Quarkus-based runtime for executing workflows
- **lemline-common** - Shared utilities and common functionality
- **lemline-docs** - Project documentation

## Development Workflow

### Branching Strategy

Lemline uses a feature branch workflow:

1. Create a branch from `main` for your work:
   ```bash
   git checkout -b feature/<feature-name>   # For new features
   git checkout -b fix/<issue-number>       # For bug fixes
   git checkout -b docs/<description>       # For documentation changes
   ```

2. Make your changes, commit them with clear, descriptive commit messages
3. Push your branch to your fork
4. Create a pull request to the `main` branch

### Commit Message Guidelines

Follow these guidelines for commit messages:

- Use the present tense ("Add feature" not "Added feature")
- Use the imperative mood ("Move cursor to..." not "Moves cursor to...")
- Limit the first line to 72 characters or less
- Reference issues and pull requests after the first line
- Use semantic prefixes for your commits:
  - `feat:` - A new feature
  - `fix:` - A bug fix
  - `docs:` - Documentation only changes
  - `style:` - Changes that do not affect the meaning of the code
  - `refactor:` - A code change that neither fixes a bug nor adds a feature
  - `perf:` - A code change that improves performance
  - `test:` - Adding missing tests or correcting existing tests
  - `chore:` - Changes to the build process or auxiliary tools

Example:
```
feat: add support for OpenAPI 3.1

This change adds support for OpenAPI 3.1 specifications in HTTP calls.
It includes new parser logic and schema validation.

Closes #123
```

### Code Style and Standards

Lemline follows Kotlin coding conventions and style:

- Use 4 spaces for indentation
- Maximum line length is 120 characters
- Use explicit type declarations for public API methods
- Include KDoc for all public APIs

The repository includes a Spotless configuration for formatting. Before submitting a pull request, run:

```bash
./gradlew spotlessApply
```

### Testing Guidelines

All contributions should include appropriate tests:

- **Unit Tests** - For testing components in isolation
- **Integration Tests** - For testing interactions between components
- **Behavior Tests** - For testing user-facing behaviors

When writing tests:

- Aim for high test coverage (>80%)
- Test both success and failure cases
- Use clear, descriptive test names
- Organize tests to mirror the structure of the code being tested

Run tests with:
```bash
./gradlew test
```

For specific test classes:
```bash
./gradlew test --tests "com.lemline.core.SomeTest"
```

## Contribution Types

### Bug Fixes

1. Search the issue tracker to check if the bug has already been reported
2. If it hasn't, create a new issue with a clear description
3. Include steps to reproduce, expected behavior, and actual behavior
4. If possible, include a minimal code sample that reproduces the issue
5. Fix the bug on a branch named `fix/<issue-number>`
6. Add tests that verify the fix
7. Submit a pull request referencing the issue

### Feature Additions

1. For significant features, first open an issue for discussion
2. Clearly describe the feature and its use cases
3. Wait for feedback and agreement on the approach
4. Implement the feature on a branch named `feature/<feature-name>`
5. Add comprehensive tests for the feature
6. Update documentation to cover the new feature
7. Submit a pull request referencing the issue

### Documentation Improvements

1. For minor documentation fixes, you can submit a PR directly
2. For larger documentation changes, open an issue for discussion first
3. Create a branch named `docs/<description>`
4. Make your changes, following the [Documentation Guide](lemline-community-style.md)
5. Submit a pull request

### Performance Improvements

1. First establish a performance baseline with benchmarks
2. Create a branch named `perf/<description>`
3. Make your performance improvements
4. Include before and after benchmark results in your PR
5. Ensure existing tests still pass
6. Submit a pull request

## Pull Request Process

1. **Open a Pull Request**:
   - Provide a clear description of the changes and the problem they solve
   - Reference any related issues
   - Include any special instructions for testing

2. **Pass Automated Checks**:
   - Ensure your code passes all CI checks
   - This includes tests, code style, and other automated verifications

3. **Code Review**:
   - At least one core contributor will review your code
   - Address any feedback or questions
   - Make requested changes as needed

4. **Final Review and Merge**:
   - Once approved, a core contributor will merge your pull request
   - In some cases, additional changes might be requested before merging

## Documentation Contributions

Good documentation is crucial for Lemline's usability. When contributing to documentation:

### Types of Documentation

1. **Reference Documentation** - Technical descriptions of APIs, configurations, and options
2. **Tutorials** - Step-by-step guides for specific tasks
3. **How-to Guides** - Practical guides for solving specific problems
4. **Explanations** - Conceptual information about how things work

### Documentation Guidelines

1. **Structure** - Follow the [Diátaxis documentation framework](https://diataxis.fr/)
2. **Clarity** - Use clear, simple language
3. **Examples** - Include practical examples where appropriate
4. **Formatting** - Use proper Markdown formatting
5. **Consistency** - Maintain consistent tone and style

For detailed documentation guidelines, see the [Documentation Style Guide](lemline-community-style.md).

## Community Support

### Ways to Support the Community

1. **Answer Questions** - Help answer questions on GitHub Discussions, Stack Overflow, or Discord
2. **Improve Documentation** - Update docs to cover common questions
3. **Create Examples** - Create example workflows for common use cases
4. **Mentor New Contributors** - Help new contributors navigate the project

### Community Channels

- **GitHub Discussions** - For feature discussions and general questions
- **Discord** - For real-time communication and community building
- **Stack Overflow** - For technical questions (use the `lemline` tag)
- **Monthly Community Call** - Join our monthly video call for project updates

## Becoming a Core Contributor

Core contributors have additional responsibilities and privileges:

### Path to Core Contributor

1. **Consistent Contributions** - Make regular, high-quality contributions
2. **Code Reviews** - Participate in reviewing other contributors' pull requests
3. **Community Engagement** - Help answer questions and support the community
4. **Project Understanding** - Demonstrate a deep understanding of the project's goals and architecture

### Core Contributor Responsibilities

1. **Code Review** - Review pull requests from the community
2. **Mentorship** - Help new contributors get started
3. **Project Direction** - Participate in discussions about project direction
4. **Quality Assurance** - Help maintain the quality and stability of the codebase

### Applying for Core Contributor Status

After you've made substantial contributions over time, you can be nominated for core contributor status by an existing core contributor, or you can express interest by contacting the project maintainers.

## Recognition and Credits

All contributors are recognized in the project:

- **Contributors List** - All contributors are listed in [CONTRIBUTORS.md](https://github.com/lemline/lemline/blob/main/CONTRIBUTORS.md)
- **Release Notes** - Significant contributions are highlighted in release notes
- **Community Spotlights** - Regular spotlights on notable contributors

## Questions and Contact

If you have any questions about contributing that aren't answered here, please:

1. Check the [Frequently Asked Questions](https://github.com/lemline/lemline/wiki/FAQ)
2. Ask in the GitHub Discussions
3. Reach out on Discord
4. Contact the maintainers at community@lemline.io

Thank you for contributing to Lemline!

---

For more information on the Lemline community, see:
- [Documentation Style Guide](lemline-community-style.md)
- [Diátaxis Documentation Framework](lemline-community-diataxis.md)