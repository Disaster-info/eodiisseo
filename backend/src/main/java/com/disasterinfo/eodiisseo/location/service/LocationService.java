package com.disasterinfo.eodiisseo.location.service;

import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.location.repository.LocationRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("!test")
public class LocationService {
    private final LocationRepository repository;
    private final Clock clock;
    public LocationService(LocationRepository repository, Clock clock) { this.repository=repository;this.clock=clock; }
    @Transactional(readOnly=true) public Map<String,Object> consentStatus(UUID member) {
        return Map.of("consented",repository.consented(member));
    }
    @Transactional public Map<String,Object> consent(UUID member, boolean consented) {
        repository.lock(member);
        repository.consent(member,consented,clock.instant());
        // Location/group reads use the database directly: no location cache survives revocation.
        return Map.of("consented",consented);
    }
    @Transactional public Map<String,Object> update(UUID member, double latitude, double longitude) {
        coordinates(latitude,longitude);
        repository.lock(member);
        if (!repository.consented(member)) throw new ApiProblem(403,"LOCATION_CONSENT_REQUIRED","위치정보 이용 동의가 필요합니다.");
        var now=clock.instant();repository.save(member,latitude,longitude,now);
        return Map.of("latitude",latitude,"longitude",longitude,"updatedAt",now);
    }
    public static void coordinates(double latitude,double longitude) {
        if (!Double.isFinite(latitude)||!Double.isFinite(longitude)||latitude < -90||latitude > 90||longitude < -180||longitude > 180)
            throw ApiProblem.bad("좌표 범위를 확인하세요.");
    }
}
