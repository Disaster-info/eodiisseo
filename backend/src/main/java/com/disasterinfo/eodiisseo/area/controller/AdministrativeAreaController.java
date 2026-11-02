package com.disasterinfo.eodiisseo.area.controller;

import com.disasterinfo.eodiisseo.area.dto.AdministrativeAreaInput;
import com.disasterinfo.eodiisseo.area.service.AdministrativeAreaImportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("!test") @RequestMapping("/api/v1/ops/admin-areas")
public class AdministrativeAreaController {
    public record Import(@NotEmpty @Size(max=1000) List<@Valid AdministrativeAreaInput> areas) {}
    private final AdministrativeAreaImportService service;
    public AdministrativeAreaController(AdministrativeAreaImportService service){this.service=service;}
    @PostMapping("/import") public Map<String,Object> importAreas(@Valid @RequestBody Import body){return Map.of("importedCount",service.importAreas(body.areas()));}
}
