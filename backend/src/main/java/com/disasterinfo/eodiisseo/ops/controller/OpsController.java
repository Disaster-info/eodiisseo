package com.disasterinfo.eodiisseo.ops.controller;
import com.disasterinfo.eodiisseo.ops.service.OpsService;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
@RestController @RequestMapping("/api/v1/ops") @Profile("!test")
public class OpsController {
    private final OpsService service;
    public OpsController(OpsService service) { this.service=service; }
    @GetMapping("/status") public Map<String,Object> status(@RequestParam(defaultValue="LIVE") String mode) { return service.status(mode); }
    @GetMapping("/disasters") public Map<String,Object> disasters(@RequestParam(required=false) String type,@RequestParam(required=false) String status,
        @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,@RequestParam(defaultValue="0") int page,
        @RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="LIVE") String mode) { return service.disasters(type,status,from,to,page,size,mode); }
    @GetMapping("/error-logs") public Map<String,Object> errors(@RequestParam(required=false) String source,@RequestParam(required=false) Instant from,
        @RequestParam(required=false) Instant to,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
        @RequestParam(defaultValue="LIVE") String mode) { return service.errors(source,from,to,page,size,mode); }
    @PostMapping("/shelters/refresh") public ResponseEntity<?> refresh(@AuthenticationPrincipal MemberPrincipal p,@RequestParam(required=false) String source,
        @RequestParam(defaultValue="LIVE") String mode) { return ResponseEntity.accepted().body(service.refresh(p.memberId(),source,mode)); }
}
