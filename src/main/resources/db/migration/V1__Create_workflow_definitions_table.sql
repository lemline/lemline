CREATE TABLE workflow_definitions (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    version VARCHAR(255) NOT NULL,
    definition TEXT NOT NULL,
    UNIQUE (name, version)
); 