package com.disasterinfo.eodiisseo.auth.security;

import java.util.UUID;
public record MemberPrincipal(UUID memberId, UUID sessionId) {}
