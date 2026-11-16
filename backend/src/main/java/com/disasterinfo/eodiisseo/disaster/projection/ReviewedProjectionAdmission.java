package com.disasterinfo.eodiisseo.disaster.projection;
import com.disasterinfo.eodiisseo.collection.operations.*;
import com.disasterinfo.eodiisseo.collection.operations.CollectionAssurance.*;
import com.disasterinfo.eodiisseo.collection.ownership.CollectionOwnership.Claim;
import java.time.*;
import java.util.*;
/** Explicit signed domain admission. Unresolved official contracts cannot be enabled by a signed checkbox. */
public final class ReviewedProjectionAdmission implements ProjectionAdmissionPort {
    private final JdbcCollectionAssurance artifacts;private final String normalizerFingerprint;
    public ReviewedProjectionAdmission(JdbcCollectionAssurance artifacts,String normalizerFingerprint){this.artifacts=artifacts;if(!normalizerFingerprint.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Reviewed normalizer build required");this.normalizerFingerprint=normalizerFingerprint;}
    public Optional<Grant> resolve(Claim c,LocalDate from,LocalDate to,Instant now){
        boolean audit=artifacts.mode()==CollectionOperationMode.DISPOSABLE_AUDIT;
        if(!audit&&!new CollectionOfficialAdmissionCatalog().productionAdmissible(c.assignment().contract()))return Optional.empty();
        return artifacts.stable(view->{var a=view.find(Kind.PROJECTION,c.assignment().contract()).orElse(null);var api=view.find(Kind.API,c.assignment().contract()).orElse(null);
            if(a==null||api==null||!a.until().isAfter(now)||!api.until().isAfter(now))return Optional.empty();var p=a.payload();var contract=api.payload();
            if(!normalizerFingerprint.equals(p.get("normalizer_sha256"))||!Boolean.TRUE.equals(p.get("incident_link_verified"))||!Boolean.TRUE.equals(p.get("coverage_allowed"))
                ||!Boolean.TRUE.equals(contract.get("bounded_range"))||!Boolean.TRUE.equals(contract.get("stable_snapshot"))||!Boolean.TRUE.equals(contract.get("incremental")))return Optional.empty();
            Instant until=a.until().isBefore(api.until())?a.until():api.until();return Optional.of(new Grant(c.assignment().target(),c.assignment().contract(),UUID.fromString((String)p.get("canonical_source")),a.version(),a.reference(),LocalDate.parse((String)p.get("initial_date")),until,true,Boolean.TRUE.equals(p.get("empty_allowed")),audit||a.synthetic()||api.synthetic()));
        });
    }
}