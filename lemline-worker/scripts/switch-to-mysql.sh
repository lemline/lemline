#!/bin/bash

# Script to switch the Lemline Worker configuration to use MySQL

echo "Switching Lemline Worker to use MySQL..."

# Create scripts directory if it doesn't exist
mkdir -p "$(dirname "$0")"

# Start MySQL docker container if not already running
if ! docker ps | grep -q lemline-mysql; then
  echo "Starting MySQL container..."
  docker-compose -f mysql-docker-compose.yaml up -d
else
  echo "MySQL container already running."
fi

# Backup the current application.properties file
CONFIG_FILE="src/main/resources/application.properties"
BACKUP_FILE="${CONFIG_FILE}.bak.$(date +%Y%m%d%H%M%S)"
cp "${CONFIG_FILE}" "${BACKUP_FILE}"
echo "Backed up current config to ${BACKUP_FILE}"

# Update the application.properties file
sed -i.tmp \
  -e 's/lemline\.database\.type=postgresql/lemline.database.type=mysql/' \
  -e 's/quarkus\.datasource\.db-kind=postgresql/quarkus.datasource.db-kind=mysql/' \
  -e 's/quarkus\.datasource\.username=postgres/quarkus.datasource.username=mysql/' \
  -e 's/quarkus\.datasource\.password=postgres/quarkus.datasource.password=mysql/' \
  -e 's|quarkus\.datasource\.jdbc\.url=jdbc:postgresql://localhost:5432/postgres|quarkus.datasource.jdbc.url=jdbc:mysql://localhost:3306/lemline|' \
  "${CONFIG_FILE}"

rm "${CONFIG_FILE}.tmp"

echo "Configuration updated to use MySQL."
echo "To switch back to PostgreSQL, use the switch-to-postgres.sh script." 