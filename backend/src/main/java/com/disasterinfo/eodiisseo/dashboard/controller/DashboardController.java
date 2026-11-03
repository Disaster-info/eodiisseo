package com.disasterinfo.eodiisseo.dashboard.controller;

import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import com.disasterinfo.eodiisseo.dashboard.DashboardDisasterQueryService;
import com.disasterinfo.eodiisseo.dashboard.DashboardException;
import com.disasterinfo.eodiisseo.dashboard.DashboardQuery;
import com.disasterinfo.eodiisseo.dashboard.DashboardService;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse;
import com.disasterinfo.eodiisseo.dashboard.dto.DisasterDetailResponse;
import com.disasterinfo.eodiisseo.dashboard.dto.DisasterListQuery;
import com.disasterinfo.eodiisseo.dashboard.dto.DisasterListResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** HTTP translation only; principal verification and domain authorization remain in existing services. */
@RestController
@ConditionalOnProperty(name = "eodiisseo.owned-http.enabled", havingValue = "true")
public class DashboardController {
    private final DashboardService dashboard;
    private final DashboardDisasterQueryService disasters;

    public DashboardController(DashboardService dashboard, DashboardDisasterQueryService disasters) {
        this.dashboard = dashboard;
        this.disasters = disasters;
    }

    @GetMapping("/api/v1/dashboard")
    public DashboardResponse dashboard(@AuthenticationPrincipal MemberPrincipal principal,
                                       @RequestParam(required = false) Double latitude,
                                       @RequestParam(required = false) Double longitude,
                                       @RequestParam(required = false) String interestAreaId) {
        MemberPrincipal member = requirePrincipal(principal);
        return dashboard.getDashboard(member.memberId(), member.sessionId(),
                new DashboardQuery(latitude, longitude, interestAreaId));
    }

    @GetMapping("/api/v1/disasters")
    public DisasterListResponse disasters(@AuthenticationPrincipal MemberPrincipal principal,
                                          @RequestParam(required = false) String type,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(defaultValue="0") int page,
                                          @RequestParam(defaultValue="20") int size) {
        MemberPrincipal member = requirePrincipal(principal);
        return disasters.getDisasters(member.memberId(), member.sessionId(), new DisasterListQuery(type, status, page, size));
    }

    @GetMapping("/api/v1/disasters/{disasterId}")
    public DisasterDetailResponse disaster(@AuthenticationPrincipal MemberPrincipal principal,
                                           @PathVariable String disasterId) {
        MemberPrincipal member = requirePrincipal(principal);
        UUID id;
        try {
            id = UUID.fromString(disasterId);
            if (!id.toString().equalsIgnoreCase(disasterId)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException failure) {
            throw new DashboardException(DashboardException.Reason.INVALID_QUERY, "A disaster UUID is required.");
        }
        return disasters.getDisaster(member.memberId(), member.sessionId(), id);
    }

    private static MemberPrincipal requirePrincipal(MemberPrincipal principal) {
        if (principal == null || principal.memberId() == null || principal.sessionId() == null) {
            throw new DashboardException(DashboardException.Reason.UNAUTHENTICATED, "An authenticated service session is required.");
        }
        return principal;
    }
}