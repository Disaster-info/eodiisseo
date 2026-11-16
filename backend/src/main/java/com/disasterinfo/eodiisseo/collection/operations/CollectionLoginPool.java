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
    private final CollectionTlsPolicy tls;
    private final java.time.Instant credentialUntil;
    private final long credentialGeneration;
    private final java.util.function.LongSupplier currentGeneration;
    public CollectionLoginPool(String jdbcUrl, String role, char[] password, String poolName, int size) {
        this(jdbcUrl,role,password,poolName,size,null,java.time.Instant.MAX,0,()->0);
    }
    public CollectionLoginPool(String jdbcUrl,String role,char[] password,String poolName,int size,
            CollectionTlsPolicy tls,java.time.Instant credentialUntil,long credentialGeneration,java.util.function.LongSupplier currentGeneration){
        this.tls=tls;this.credentialUntil=Objects.requireNonNull(credentialUntil);this.credentialGeneration=credentialGeneration;this.currentGeneration=Objects.requireNonNull(currentGeneration);
        this.role=Objects.requireNonNull(role);
        if (!role.matches("[a-z][a-z0-9_]{0,62}") || size<1 || size>8 || password==null || password.length<24)
            throw new IllegalArgumentException("Explicit bounded LOGIN pool required");
        // URL is deployment input, never logged by this class. Do not accept credentials in it.
        if (jdbcUrl==null || !jdbcUrl.startsWith("jdbc:postgresql://") || jdbcUrl.contains("?") || jdbcUrl.contains("@"))
            throw new IllegalArgumentException("Credential-free explicit PostgreSQL URL required");
        var endpoint=java.net.URI.create(jdbcUrl.substring(5));
        if(tls==null && !jdbcUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:55433/collection4c_[a-f0-9]{32}"))throw new IllegalArgumentException("Verified TLS required outside the approved disposable audit database");
        verifyLease();
        var config=new HikariConfig();
        if(tls!=null){config.addDataSourceProperty("sslmode","verify-full");config.addDataSourceProperty("sslrootcert",tls.rootCertificate().toString());}

        config.setJdbcUrl(jdbcUrl);config.setUsername(role);config.setPassword(new String(password));
        config.setPoolName(poolName);config.setMaximumPoolSize(size);config.setMinimumIdle(0);
        config.addDataSourceProperty("prepareThreshold","0"); // DISCARD ALL invalidates server prepared statements.
        config.setAutoCommit(true);config.setConnectionTimeout(5000);config.setInitializationFailTimeout(-1);
        pool=new HikariDataSource(config);
    }
    private void verifyLease(){
        if(!credentialUntil.isAfter(java.time.Instant.now())||credentialGeneration!=currentGeneration.getAsLong())throw new IllegalStateException("Credential missing, expired or superseded");
        if(tls!=null)tls.verify();
    }
    @Override public Connection getConnection() throws SQLException {
        try{verifyLease();}catch(RuntimeException rejected){throw new SQLException("LOGIN lease rejected","28000");}
        Connection c;
        try{c=pool.getConnection();}catch(SQLException rejected){throw new SQLException("LOGIN connection rejected","08001");}
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
            verifyLease();return c;
        } catch(SQLException|RuntimeException failure) { c.close();throw new SQLException("LOGIN pool checkout rejected", "28000"); }
    }
    @Override public Connection getConnection(String user,String password) throws SQLException {throw new SQLException("Per-request credentials forbidden", "28000");}
    @Override public <T>T unwrap(Class<T> type) throws SQLException {throw new SQLException("Credential-bearing delegate is private");}
    @Override public boolean isWrapperFor(Class<?> type) {return false;}
    @Override public void close() {pool.close();pool.setPassword(null);}
}
