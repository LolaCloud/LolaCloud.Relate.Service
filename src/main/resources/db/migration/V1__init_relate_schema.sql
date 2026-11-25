CREATE TABLE IF NOT EXISTS database_instances (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    engine VARCHAR(50) NOT NULL,
    version VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    host_sni VARCHAR(255) NOT NULL UNIQUE,
    port INT NOT NULL,
    internal_port INT NOT NULL UNIQUE,
    admin_username VARCHAR(100) NOT NULL,
    admin_password VARCHAR(255) NOT NULL,
    default_database VARCHAR(100) NOT NULL,
    tier VARCHAR(50) NOT NULL,
    storage_gb INT NOT NULL DEFAULT 10,
    connection_string TEXT NOT NULL,
    container_id VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_database_instances_status ON database_instances(status);
CREATE INDEX IF NOT EXISTS idx_database_instances_engine ON database_instances(engine);

CREATE TABLE IF NOT EXISTS database_backups (
    id UUID PRIMARY KEY,
    instance_id UUID NOT NULL REFERENCES database_instances(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    size_bytes BIGINT,
    status VARCHAR(50) NOT NULL,
    storage_path TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_database_backups_instance_id ON database_backups(instance_id);
