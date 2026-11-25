package com.miranda.luna.relate.domain.model;

public enum EngineType {
    POSTGRESQL(5432, "17", "postgres"),
    MYSQL(3306, "8.0", "mysql"),
    SQLSERVER(1433, "2022", "sqlserver");

    private final int defaultPort;
    private final String defaultVersion;
    private final String traefikEntryPoint;

    EngineType(int defaultPort, String defaultVersion, String traefikEntryPoint) {
        this.defaultPort = defaultPort;
        this.defaultVersion = defaultVersion;
        this.traefikEntryPoint = traefikEntryPoint;
    }

    public int getDefaultPort() {
        return defaultPort;
    }

    public String getDefaultVersion() {
        return defaultVersion;
    }

    public String getTraefikEntryPoint() {
        return traefikEntryPoint;
    }

    public String buildConnectionString(String hostSni, int port, String username, String password, String database) {
        return switch (this) {
            case POSTGRESQL -> String.format("postgresql://%s:%s@%s:%d/%s?sslmode=require",
                    username, password, hostSni, port, database);
            case MYSQL -> String.format("mysql://%s:%s@%s:%d/%s?useSSL=true&requireSSL=true",
                    username, password, hostSni, port, database);
            case SQLSERVER -> String.format("sqlserver://%s:%d;databaseName=%s;user=%s;password=%s;encrypt=true;trustServerCertificate=false",
                    hostSni, port, database, username, password);
        };
    }
}
