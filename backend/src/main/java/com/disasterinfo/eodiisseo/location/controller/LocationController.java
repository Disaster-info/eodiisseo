package com.disasterinfo.eodiisseo.location.controller;

import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import com.disasterinfo.eodiisseo.auth.service.OwnedRateLimiter;
import com.disasterinfo.eodiisseo.location.dto.LocationDto.*;
import com.disasterinfo.eodiisseo.location.service.LocationService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("!test") @RequestMapping("/api/v1/members/me")
public class LocationController {
    private final LocationService service;private final OwnedRateLimiter limits;
    public LocationController(LocationService service,OwnedRateLimiter limits){this.service=service;this.limits=limits;}
    @GetMapping("/location-consent") public Map<String,Object> consent(@AuthenticationPrincipal MemberPrincipal p) {
        return service.consentStatus(p.memberId());
    }
    @PatchMapping("/location-consent") public Map<String,Object> consent(@AuthenticationPrincipal MemberPrincipal p,@Valid @RequestBody Consent body) {
        return service.consent(p.memberId(),body.consented());
    }
    @PutMapping("/location") public Map<String,Object> update(@AuthenticationPrincipal MemberPrincipal p,@Valid @RequestBody Coordinates body) {
        limits.checkLocationWrite(p.memberId());
        return service.update(p.memberId(),body.latitude(),body.longitude());
    }
}
