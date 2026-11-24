package com.disasterinfo.eodiisseo.auth.service;

import java.time.Instant;
import java.util.UUID;

/** Published synchronously within the account creation transaction. */
public record MemberCreated(UUID memberId, Instant createdAt) {}
