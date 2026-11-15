package com.disasterinfo.eodiisseo.collection.operations;

import com.zaxxer.hikari.*;
import org.springframework.jdbc.datasource.AbstractDataSource;
import java.sql.*;
import java.util.*;

/** One credential in one process/composition root. Never register a cross-plane credential registry.
 * The pool is deliberately not exposed as a bean, through unwrap, or credential getters. */
public final class CollectionLoginPool extends AbstractDataSource implements AutoCloseable {
    private final HikariDataSource pool;
    private final String role;
    public CollectionLoginPool(String jdbcUrl, String role, char[] password, String poolName, int size) {
        this.role=Objects.requireNonNull(role);
        if (!role.matches("[a-z][a-z0-9_]{0,62}") || size<1 || size>8 || password==null || password.length<24)
            throw new IllegalArgumentException("Explicit bounded LOGIN pool required");
        // URL is deployment input, never logged by this class. Do not accept credentials in it.
        if (jdbcUrl==null || !jdbcUrl.startsWith("jdbc:postgresql://") || jdbcUrl.contains("?") || jdbcUrl.contains("@"))
            throw new IllegalArgumentException("Credential-free explicit PostgreSQL URL required");
        var config=new HikariConfig();
        config.setJdbcUrl(jdbcUrl);config.setUsername(role);config.setPassword(new String(password));
        config.setPoolName(poolName);config.setMaximumPoolSize(size);config.setMinimumIdle(0);
        config.addDataSourceProperty("prepareThreshold","0"); // DISCARD ALL invalidates server prepared statements.
        config.setAutoCommit(true);config.setConnectionTimeout(5000);config.setInitializationFailTimeout(-1);
        pool=new HikariDataSource(config);
    }
    @Override public Connection getConnection() throws SQLException {
        Connection c=pool.getConnection();
        try {
            if (!c.getAutoCommit()) throw new SQLException("Unclean pooled transaction");
            // Hikari resets JDBC properties, not arbitrary PostgreSQL session settings.
            // DISCARD cannot run within the gateway transaction; it runs only on a new checkout.
            try(var s=c.createStatement()) {
                s.execute("DISCARD ALL");
                s.execute("SET TIME ZONE 'UTC'");
                try(var r=s.executeQuery("select session_user,current_user")) {
                    if (!r.next() || !role.equals(r.getString(1)) || !role.equals(r.getString(2)))
                        throw new SQLException("LOGIN principal mismatch");
                }
            }
            return c;
        } catch(SQLException failure) { c.close();throw new SQLException("LOGIN pool checkout rejected", "28000"); }
    }
    @Override public Connection getConnection(String user,String password) throws SQLException {throw new SQLException("Per-request credentials forbidden", "28000");}
    @Override public <T>T unwrap(Class<T> type) throws SQLException {throw new SQLException("Credential-bearing delegate is private");}
    @Override public boolean isWrapperFor(Class<?> type) {return false;}
    @Override public void close() {pool.close();pool.setPassword(null);}
}
