package com.disasterinfo.eodiisseo.collection.domain;

import java.util.Map;

/** Immutable provider record, not an inferred disaster or impact region. */
public record CollectedRecord(String key,Map<String,Object> payload) {
    public CollectedRecord { if(key==null||key.isBlank()||key.length()>255||payload==null)throw new IllegalArgumentException("Invalid source record"); }
}
