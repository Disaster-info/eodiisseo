package com.disasterinfo.eodiisseo.ops.dto;

import java.util.UUID;
import java.util.List;
import java.util.Map;

public final class OpsDto {
    private OpsDto() {}
    /** Internal query result; HTTP pagination fields are assembled by the service. */
    public record PageResult(List<Map<String,Object>> rows, long total) {}
    public record Accepted(UUID refreshRequestId,String requestType,String status) {
        public Accepted {
            if(refreshRequestId==null || !("RUN".equals(requestType)||"BATCH".equals(requestType)) || !"ACCEPTED".equals(status))
                throw new IllegalArgumentException("Invalid collection acceptance");
        }
    }
}
