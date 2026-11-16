package com.disasterinfo.eodiisseo.group.dto;

import java.util.UUID;
import jakarta.validation.constraints.*;

/** HTTP request bodies and their field constraints. */
public final class GroupDto {
    private GroupDto() {}
    public record GroupInput(String name,String category) {}
    public record Join(@NotBlank @Size(max=32) String inviteCode) {}
    public record Transfer(@NotNull UUID newAdminMemberId) {}
    public record Sharing(@NotNull Boolean enabled) {}
}
