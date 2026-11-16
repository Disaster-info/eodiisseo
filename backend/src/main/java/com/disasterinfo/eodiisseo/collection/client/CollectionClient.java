package com.disasterinfo.eodiisseo.collection.client;

import com.disasterinfo.eodiisseo.collection.domain.CollectedRecord;
import java.util.List;
import java.util.Set;

public interface CollectionClient {
    Set<String> configuredSources();
    List<CollectedRecord> fetch(String source,Runnable heartbeat);
}
