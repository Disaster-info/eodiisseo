package com.disasterinfo.eodiisseo.ops.port;

import java.util.UUID;
import com.disasterinfo.eodiisseo.ops.dto.OpsDto.Accepted;

/** Implemented by the collection owner once its transactional run/batch + lease worker is available. */
public interface ShelterRefreshGateway {
    Accepted request(UUID requestedBy,String sourceEndpointKey,String mode);
}
