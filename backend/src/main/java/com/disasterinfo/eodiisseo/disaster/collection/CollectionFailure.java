package com.disasterinfo.eodiisseo.disaster.collection;
/** Safe diagnostics: never retain URLs, credentials or provider response text as exception causes. */
public final class CollectionFailure extends RuntimeException {
    private final String code;
    public CollectionFailure(String code) { super("Official collection failed: "+code); this.code=code; }
    public String code(){return code;}
}
