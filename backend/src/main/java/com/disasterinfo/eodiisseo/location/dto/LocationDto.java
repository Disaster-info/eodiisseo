package com.disasterinfo.eodiisseo.location.dto;

import jakarta.validation.constraints.*;

public final class LocationDto {
    private LocationDto() {}
    public record Consent(@NotNull Boolean consented) {}
    public record Coordinates(@NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
                              @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) {}
}
