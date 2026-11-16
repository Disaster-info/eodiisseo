package com.disasterinfo.eodiisseo.collection.operations;
import java.util.Map;
/** Explicit pinned reviewer public keys, supplied by reviewed deployment configuration. No discovery or test defaults. */
public interface CollectionReviewTrustRootsPort {Map<String,CollectionAssurance.TrustRoot> roots();}