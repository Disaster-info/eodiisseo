package com.disasterinfo.eodiisseo.collection.operations;
import javax.sql.DataSource;
import java.sql.*;
/** Audit mode is explicitly constructed only in tests, never selected from an application flag. */
public enum CollectionOperationMode {
    PRODUCTION, DISPOSABLE_AUDIT;
    public void verify(DataSource dataSource){
        if(this==DISPOSABLE_AUDIT){try(var c=dataSource.getConnection()){
            if(!c.getMetaData().getURL().matches("jdbc:postgresql://127\\.0\\.0\\.1:55433/collection(?:4a|4b1)_[a-f0-9]{32}"))throw new IllegalArgumentException("Disposable collection audit database only");
        }catch(SQLException failed){throw new IllegalStateException("Audit database unavailable");}}
    }
    public boolean accepts(boolean synthetic){return this==DISPOSABLE_AUDIT||!synthetic;}
}