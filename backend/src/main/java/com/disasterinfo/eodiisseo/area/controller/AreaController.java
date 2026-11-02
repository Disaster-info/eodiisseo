package com.disasterinfo.eodiisseo.area.controller;

import com.disasterinfo.eodiisseo.area.dto.AreaDto.*;
import com.disasterinfo.eodiisseo.area.service.AreaService;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("!test") @RequestMapping("/api/v1/interest-areas")
public class AreaController {
    private final AreaService service;
    public AreaController(AreaService service){this.service=service;}
    @PostMapping public ResponseEntity<?> create(@AuthenticationPrincipal MemberPrincipal p,@Valid @RequestBody Create body){return ResponseEntity.status(201).body(service.create(p.memberId(),body.regionCode(),body.displayName()));}
    @GetMapping public Map<String,Object> list(@AuthenticationPrincipal MemberPrincipal p){return service.list(p.memberId());}
    @PatchMapping("/{id}") public Map<String,Object> update(@AuthenticationPrincipal MemberPrincipal p,@PathVariable UUID id,@Valid @RequestBody Update body){return service.update(p.memberId(),id,body.regionCode(),body.displayName());}
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@AuthenticationPrincipal MemberPrincipal p,@PathVariable UUID id){service.delete(p.memberId(),id);return ResponseEntity.noContent().build();}
}
