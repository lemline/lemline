-- Use the table name from com.lemline.runner.models.DefinitionModel
CREATE TABLE IF NOT EXISTS lemline_definitions
(
    namespace  VARCHAR(255) COLLATE "C" NOT NULL,
    name       VARCHAR(255) COLLATE "C" NOT NULL,
    version    VARCHAR(255) COLLATE "C" NOT NULL,
    definition TEXT                     NOT NULL,
    created_at TIMESTAMPTZ(6)           NOT NULL,
    updated_at TIMESTAMPTZ(6),
    PRIMARY KEY (namespace, name, version)
);

-- Create an index for efficient querying on namespace + name
CREATE INDEX IF NOT EXISTS idx_lemline_definitions_name ON lemline_definitions (namespace, name);

-- Create composite index for efficient version ordering (used by listByName with ORDER BY version)
CREATE INDEX IF NOT EXISTS idx_lemline_definitions_name_version ON lemline_definitions (namespace, name, version);

-- Index optimized for fetching latest version (ORDER BY version DESC LIMIT 1)
-- The DESC ordering helps PostgreSQL optimize descending scans
CREATE INDEX IF NOT EXISTS idx_lemline_definitions_latest
    ON lemline_definitions (namespace, name, version DESC);
