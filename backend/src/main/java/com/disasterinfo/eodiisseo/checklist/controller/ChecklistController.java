package com.disasterinfo.eodiisseo.checklist.controller;
import com.disasterinfo.eodiisseo.checklist.service.ChecklistService;

import com.disasterinfo.eodiisseo.checklist.dto.ChecklistDto.*;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;

@RestController @RequestMapping("/api/v1/checklists") @Profile("!test")
public class ChecklistController {
    private final ChecklistService service;
    public ChecklistController(ChecklistService service) { this.service=service; }
    @GetMapping public Map<String,Object> get(@AuthenticationPrincipal MemberPrincipal p,@RequestParam String disasterType) { return service.get(p.memberId(),disasterType); }
    @PatchMapping("/{checklistId}/items/{itemId}/completion") public Map<String,Object> complete(@AuthenticationPrincipal MemberPrincipal p,@PathVariable UUID checklistId,@PathVariable UUID itemId,@Valid @RequestBody Completion b) { return service.complete(p.memberId(),checklistId,itemId,b.completed()); }
}
