package com.disasterinfo.eodiisseo.area.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.Map;

/** Operator supplies a verified dataset, never a guessed or generated boundary. */
public record AdministrativeAreaInput(@NotBlank @Size(max=20) String regionCode,
        @NotBlank @Size(max=100) String name,@NotBlank String level,
        @Size(max=20) String parentRegionCode,@NotNull Boolean active,
        @NotBlank String sourceUrl,@NotBlank @Size(max=30) String sourceProvider,
        @NotBlank @Size(max=20) String sourceAreaCode,@NotNull @Min(1900) Integer baseYear,
        @NotNull Instant sourceUpdatedAt,Double latitude,Double longitude,Map<String,Object> boundary) {}
