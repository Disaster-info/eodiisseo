package com.disasterinfo.eodiisseo.collection.operations;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
/** Registered only by explicit management configuration; existing /api/v1/ops security namespace applies. */
@RestController
@org.springframework.context.annotation.Profile("!test")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="eodiisseo.collection.operations.admin-enabled",havingValue="true")
@RequestMapping("/api/v1/ops/collection-administration")
public final class CollectionOperationsController {
    private final CollectionOperationsService service;
    public CollectionOperationsController(CollectionOperationsService service){this.service=service;}
    @GetMapping public Object investigate(@AuthenticationPrincipal MemberPrincipal actor){return service.investigate(actor);}
    @GetMapping("/{run}") public Object inspect(@AuthenticationPrincipal MemberPrincipal actor,@PathVariable UUID run){return service.inspect(actor,run);}
    @PostMapping("/resolutions") public Object resolve(@AuthenticationPrincipal MemberPrincipal actor,@RequestBody CollectionOperationsService.Resolution request){return Map.of("resolutionId",service.resolve(actor,request));}
    public record Review(String step,UUID parent,String reference,Instant validUntil) {}
    @PostMapping("/reviews") public Object review(@AuthenticationPrincipal MemberPrincipal actor,@RequestBody Review request){return Map.of("reviewId",service.review(actor,request.step(),request.parent(),request.reference(),request.validUntil()));}
    @PostMapping("/operations") public Object approve(@AuthenticationPrincipal MemberPrincipal actor,@RequestBody CollectionOperationsService.Operation request){return Map.of("approvalId",service.approveOperation(actor,request));}
    @PostMapping("/components/{kind}") public Object component(@AuthenticationPrincipal MemberPrincipal actor,@PathVariable CollectionOperationalEvidencePort.Kind kind){return Map.of("approvalId",service.approveComponent(actor,kind));}
    public record Revoke(String reference) {}
    @PostMapping("/approvals/{id}/revoke") public void revoke(@AuthenticationPrincipal MemberPrincipal actor,@PathVariable UUID id,@RequestBody Revoke request){service.revoke(actor,id,request.reference());}
}