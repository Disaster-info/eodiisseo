package com.disasterinfo.eodiisseo.collection.controller;

import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import com.disasterinfo.eodiisseo.collection.service.CollectionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("!test") @RequestMapping("/api/v1/ops/collections")
public class CollectionController {
    public record Request(@NotBlank String source) {}
    private final CollectionService service;
    public CollectionController(CollectionService service){this.service=service;}
    @GetMapping("/sources") public Map<String,Object> sources(){return Map.of("sources",service.sources());}
    @PostMapping public ResponseEntity<?> request(@AuthenticationPrincipal MemberPrincipal p,@Valid @RequestBody Request body){return ResponseEntity.accepted().body(service.request(p.memberId(),body.source(),"LIVE",false));}
}
