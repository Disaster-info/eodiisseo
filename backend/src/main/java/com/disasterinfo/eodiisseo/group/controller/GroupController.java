package com.disasterinfo.eodiisseo.group.controller;
import com.disasterinfo.eodiisseo.group.service.GroupService;

import com.disasterinfo.eodiisseo.group.dto.GroupDto.*;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import com.disasterinfo.eodiisseo.auth.service.OwnedRateLimiter;

@RestController @RequestMapping("/api/v1/groups") @Profile("!test")
public class GroupController {
    private final GroupService service;
    private final OwnedRateLimiter limiter;
    public GroupController(GroupService service,OwnedRateLimiter limiter) { this.service=service;this.limiter=limiter; }
    @PostMapping public ResponseEntity<?> create(@AuthenticationPrincipal MemberPrincipal p,@RequestBody GroupInput b) { return ResponseEntity.status(201).body(service.create(p.memberId(),b.name(),b.category())); }
    @GetMapping public Map<String,Object> list(@AuthenticationPrincipal MemberPrincipal p) { return service.list(p.memberId()); }
    @GetMapping("/{groupId}") public Map<String,Object> detail(@AuthenticationPrincipal MemberPrincipal p,@PathVariable UUID groupId) { return service.detail(p.memberId(),groupId); }
    @PostMapping("/join") public ResponseEntity<?> join(@AuthenticationPrincipal MemberPrincipal p,@Valid @RequestBody Join b) { limiter.checkGroupJoin(p.memberId());return ResponseEntity.status(201).body(service.join(p.memberId(),b.inviteCode())); }
    @DeleteMapping("/{groupId}/members/{memberId}") public ResponseEntity<Void> exclude(@AuthenticationPrincipal MemberPrincipal p,@PathVariable UUID groupId,@PathVariable UUID memberId) { service.exclude(p.memberId(),groupId,memberId);return ResponseEntity.noContent().build(); }
    @PatchMapping("/{groupId}/admin") public Map<String,Object> transfer(@AuthenticationPrincipal MemberPrincipal p,@PathVariable UUID groupId,@Valid @RequestBody Transfer b) { return service.transfer(p.memberId(),groupId,b.newAdminMemberId()); }
    @DeleteMapping("/{groupId}/members/me") public ResponseEntity<Void> leave(@AuthenticationPrincipal MemberPrincipal p,@PathVariable UUID groupId) { service.leave(p.memberId(),groupId);return ResponseEntity.noContent().build(); }
    @PatchMapping("/{groupId}/members/me/location-sharing") public Map<String,Object> sharing(@AuthenticationPrincipal MemberPrincipal p,@PathVariable UUID groupId,@Valid @RequestBody Sharing b) { return service.sharing(p.memberId(),groupId,b.enabled()); }
    @GetMapping("/{groupId}/members/locations") public Map<String,Object> locations(@AuthenticationPrincipal MemberPrincipal p,@PathVariable UUID groupId) { return service.locations(p.memberId(),groupId); }
    @GetMapping("/{groupId}/members/disaster-status") public Map<String,Object> impacts(@AuthenticationPrincipal MemberPrincipal p,@PathVariable UUID groupId) { return service.impacts(p.memberId(),groupId); }
    @PatchMapping("/{groupId}") public Map<String,Object> edit(@AuthenticationPrincipal MemberPrincipal p,@PathVariable UUID groupId,@RequestBody GroupInput b) { return service.edit(p.memberId(),groupId,b.name(),b.category()); }
    @DeleteMapping("/{groupId}") public ResponseEntity<Void> delete(@AuthenticationPrincipal MemberPrincipal p,@PathVariable UUID groupId) { service.delete(p.memberId(),groupId);return ResponseEntity.noContent().build(); }
}
