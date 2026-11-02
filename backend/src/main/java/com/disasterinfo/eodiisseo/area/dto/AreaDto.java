package com.disasterinfo.eodiisseo.area.dto;

import jakarta.validation.constraints.*;

public final class AreaDto {
    private AreaDto() {}
    public record Create(@NotBlank @Size(max=20) String regionCode,@NotBlank @Size(max=50) String displayName) {}
    public record Update(@Size(min=1,max=20) String regionCode,@Size(min=1,max=50) String displayName) {}
}
