package org.tsicoop.nexus.framework;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.*;

/**
 * Sandboxed connection pool for LLM-generated ad-hoc analytical queries (Analytics.java).
 * Authenticates as the nexus_readonly role (SELECT-only on an explicit table allowlist,
 * see db/init.sql), and is kept deliberately separate from the main PoolDB pool so an
 * ad-hoc query can never starve connections the rest of the application needs, and shows
 * up distinctly in pg_stat_activity.
 */
public class ReadOnlyPoolDB extends DB {

    private static volatile HikariDataSource readOnlyDataSource = null;

    private static synchronized void initReadOnlyDataSource() {
        if (readOnlyDataSource != null) return;

        HikariConfig config = new HikariConfig();

        config.setJdbcUrl(SystemConfig.getAppConfig().getProperty("framework.db.host") + "/" + SystemConfig.getAppConfig().getProperty("framework.db.name"));
        config.setUsername(SystemConfig.getAppConfig().getProperty("framework.db.ro.user"));
        config.setPassword(SystemConfig.getAppConfig().getProperty("framework.db.ro.password"));

        // Deliberately small - this pool only ever serves ad-hoc analytical queries.
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "50");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        readOnlyDataSource = new HikariDataSource(config);
        System.out.println("HikariCP DataSource initialized for nexus_readonly (Thread-Safe).");
    }

    public ReadOnlyPoolDB() throws SQLException {
        super();
        this.con = createConnection(true);
    }

    public Connection getConnection() {
        return con;
    }

    public Connection createConnection(boolean autocommit) throws SQLException {
        try {
            Class.forName("org.postgresql.Driver");

            if (readOnlyDataSource == null) {
                initReadOnlyDataSource();
            }

            Connection connection = readOnlyDataSource.getConnection();
            connection.setAutoCommit(autocommit);
            // Belt-and-suspenders: statement_timeout is also set at the role level
            // (db/init.sql), but a connection could be pooled from before that ran.
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET statement_timeout = '3000ms'");
            }
            return connection;
        } catch (ClassNotFoundException e) {
            throw new SQLException("PostgreSQL Driver not found", e);
        }
    }
}
