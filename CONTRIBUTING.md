# Contributing to Lemline

Thank you for your interest in contributing to Lemline! This document provides guidelines and best practices for contributing to the project.

## 🛠 Development Setup

### Prerequisites

- JDK 17 or later
- Gradle 8.5 or later
- Docker (for running tests with PostgreSQL and MySQL)
- A supported message broker (Kafka or RabbitMQ) for integration tests

### Building the Project

```bash
./gradlew build
```

### Running Tests

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "com.lemline.runner.tests.YourTestClass"

# Run tests in specific module
./gradlew :lemline-runner:test 
```

## 📝 Coding Standards

### Kotlin Style Guide

- Follow the official [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use 4 spaces for indentation
- Maximum line length: 120 characters
- Document public APIs with KDoc

### Architecture Principles

- Follow SOLID principles
- Implement DRY (Don't Repeat Yourself)
- Keep It Simple (KISS)
- You Aren't Gonna Need It (YAGNI)
- Follow OWASP security guidelines

### Database Best Practices

- No ORM - use native SQL queries for better control and performance
- Ensure all changes work across all supported databases (PostgreSQL, MySQL, H2)
- Use database-agnostic SQL syntax where possible
- For database-specific features, provide alternative implementations
- Use batch operations for bulk inserts/updates
- Create appropriate indexes for performance
- Follow migration best practices

## 🧪 Testing Requirements

### Test Coverage

- Maintain high test coverage (aim for >80%)
- Write unit tests for all new features
- Include integration tests for database and messaging operations
- Test edge cases and error scenarios

### Testing Frameworks

- Use quarkus-junit5 for integration tests
- Use Mockk for mocking
- Follow test-driven development (TDD) when possible

### Database Testing

- Test with with all supported  databases (PostgreSQL, MySQL, H2)
- Use test containers for database testing
- Clean up test data after each test

## 🔄 Contribution Workflow

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/your-feature-name`)
3. Make your changes
4. Write tests for your changes
5. Ensure all tests pass
6. Format your code according to Kotlin conventions
7. Commit your changes (`git commit -m 'Add some feature'`)
8. Push to the branch (`git push origin feature/your-feature-name`)
9. Open a Pull Request

### Pull Request Guidelines

- Provide a clear description of changes
- Reference related issues
- Include test coverage information
- Update documentation if needed
- Ensure CI checks pass

## 📚 Documentation

- Update README.md for significant changes
- Document new features in the appropriate module
- Keep API documentation up to date
- Include examples for complex features

## 🔒 Security Considerations

- Follow OWASP guidelines
- Never commit sensitive information
- Use environment variables for configuration
- Implement proper input validation
- Follow secure coding practices

## 🚀 Performance Guidelines

- Profile new features for performance impact
- Implement caching where beneficial
- Optimize database queries
- Monitor memory usage

## 🤝 Code Review Process

- Be responsive to review comments
- Address all feedback
- Keep discussions constructive
- Be open to suggestions
- Maintain a professional tone

## 📜 License

By contributing to Lemline, you agree that your contributions will be licensed under the [Business Source License 1.1](LICENSE.md).

## 💡 Getting Help

If you need help or have questions:
- Open an issue
- Join our community chat
- Check the documentation
- Review existing issues and PRs

Thank you for contributing to Lemline! 🎉 