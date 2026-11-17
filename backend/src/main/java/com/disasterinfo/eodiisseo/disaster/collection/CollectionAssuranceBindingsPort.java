package com.disasterinfo.eodiisseo.disaster.collection;
import com.disasterinfo.eodiisseo.collection.execution.CollectionRawPrivacyPort;
import java.util.*;
import java.util.function.Supplier;
/** Deployment-owned secret references and reviewed profiles. This interface never reads environment/files by default. */
public interface CollectionAssuranceBindingsPort {
    ReviewedOfficialHttpSender sender();
    Map<String,Supplier<String>> decodedCredentials();
    Set<String> knownSecretRepresentations();
    CollectionRawPrivacyPort privacy();
}