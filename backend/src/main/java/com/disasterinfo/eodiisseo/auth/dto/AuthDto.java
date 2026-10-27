package com.disasterinfo.eodiisseo.auth.dto;

import java.util.Map;
import jakarta.validation.constraints.*;

/** HTTP request bodies and their field constraints. */
public final class AuthDto {
    private AuthDto() {}
    public record Login(Map<String,Object> body, boolean created) {}
    public record Google(@NotBlank @Size(max=16384) String idToken) {}
    public record Kakao(@NotBlank @Size(max=16384) String accessToken) {}
    public record Credential(@NotBlank @Size(max=16384) String socialCredential) {}
    public record Recovery(@NotBlank String provider,@NotBlank @Size(max=16384) String socialCredential) {}
    public record Refresh(@NotBlank @Size(max=512) String refreshToken) {}
}
