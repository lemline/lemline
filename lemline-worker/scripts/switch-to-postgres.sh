#!/bin/bash

# Script to switch the Lemline Worker configuration to use PostgreSQL

echo "Switching Lemline Worker to use PostgreSQL..."

# Create scripts directory if it doesn't exist
mkdir -p "$(dirname "$0")"

# Backup the current application.properties file
CONFIG_FILE="src/main/resources/application.properties"
BACKUP_FILE="${CONFIG_FILE}.bak.$(date +%Y%m%d%H%M%S)"
cp "${CONFIG_FILE}" "${BACKUP_FILE}"
echo "Backed up current config to ${BACKUP_FILE}"

# Update the application.properties file
sed -i.tmp \
  -e 's/lemline\.database\.type=mysql/lemline.database.type=postgresql/' \
  -e 's/quarkus\.datasource\.db-kind=mysql/quarkus.datasource.db-kind=postgresql/' \
  -e 's/quarkus\.datasource\.username=mysql/quarkus.datasource.username=postgres/' \
  -e 's/quarkus\.datasource\.password=mysql/quarkus.datasource.password=postgres/' \
  -e 's|quarkus\.datasource\.jdbc\.url=jdbc:mysql://localhost:3306/lemline|quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/postgres|' \
  "${CONFIG_FILE}"

rm "${CONFIG_FILE}.tmp"

echo "Configuration updated to use PostgreSQL."
echo "Don't forget to ensure your PostgreSQL server is running." 