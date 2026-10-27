package com.disasterinfo.eodiisseo.checklist.dto;

import jakarta.validation.constraints.*;

/** HTTP request bodies and their field constraints. */
public final class ChecklistDto {
    private ChecklistDto() {}
    public record Completion(@NotNull Boolean completed) {}
}
