# Lemline Worker

This module contains the core workflow runtime for the Lemline project.

## Database Configuration

The Lemline Worker supports both PostgreSQL (default) and MySQL databases. You can configure the database through the application properties.

### Using PostgreSQL (Default)

PostgreSQL is configured by default. To explicitly use PostgreSQL, ensure the following settings in your `application.properties`:

```properties
# Database selection
lemline.database.type=postgresql

# PostgreSQL configuration
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=postgres
quarkus.datasource.password=postgres
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/postgres
```

### Using MySQL

To use MySQL instead of PostgreSQL, follow these steps:

1. Start the MySQL container using the provided Docker Compose file:

```bash
docker-compose -f mysql-docker-compose.yaml up -d
```

2. Update your `application.properties` to use MySQL:

```properties
# Database selection
lemline.database.type=mysql

# MySQL configuration
quarkus.datasource.db-kind=mysql
quarkus.datasource.username=mysql
quarkus.datasource.password=mysql
quarkus.datasource.jdbc.url=jdbc:mysql://localhost:3306/lemline
```

3. Comment out the PostgreSQL configuration if it exists:

```properties
# PostgreSQL configuration (disabled)
#quarkus.datasource.db-kind=postgresql
#quarkus.datasource.username=postgres
#quarkus.datasource.password=postgres
#quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/postgres
```

### Database Migration Structure

The database migrations are organized in database-specific folders:

- `src/main/resources/db/migration/postgresql/` - Contains PostgreSQL-specific migrations
- `src/main/resources/db/migration/mysql/` - Contains MySQL-specific migrations

The appropriate migrations are applied automatically based on the selected database type.

## Running Tests

The tests are organized to run against both PostgreSQL and MySQL. By default, only PostgreSQL tests are run.

### Running PostgreSQL Tests

PostgreSQL tests run by default:

```bash
./gradlew lemline-worker:test
```

### Running MySQL Tests

To run MySQL tests, use:

```bash
./gradlew lemline-worker:test -DincludeMySQL=true
```

### Running Both Database Tests

To run tests against both databases:

```bash
./gradlew lemline-worker:test -DincludeMySQL=true
```

### Skipping PostgreSQL Tests

To run only MySQL tests and skip PostgreSQL tests:

```bash
./gradlew lemline-worker:test -DskipPostgres=true -DincludeMySQL=true
```

## Development Mode

You can run your application in dev mode that enables live coding using:

```bash
./gradlew lemline-worker:quarkusDev
```

## Packaging and Running the Application

The application can be packaged using:

```bash
./gradlew lemline-worker:build
```

It produces the `quarkus-run.jar` file in the `build/quarkus-app/` directory.
Be aware that it's not an _über-jar_ as the dependencies are copied into the `build/quarkus-app/lib/` directory. 