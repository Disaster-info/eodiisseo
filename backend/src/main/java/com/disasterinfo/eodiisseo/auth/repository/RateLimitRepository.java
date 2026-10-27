package com.disasterinfo.eodiisseo.auth.repository;

import java.util.*;
import java.time.Instant;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

/** SQL and bind parameters for the auth feature. Transactions are owned by the service. */
@Repository @Profile("!test")
public class RateLimitRepository {
    private final OwnedDatabase db;
    public RateLimitRepository(OwnedDatabase db) { this.db=db; }

    public int deleteExpiredWindows(UUID member, Instant cutoff) {
        return db.update("delete from auth_request_window where member_id=? and window_start<?",member,cutoff);
    }

    public Map<String,Object> incrementGroupJoinWindow(UUID member, Instant windowStart) {
        return db.one("insert into auth_request_window(member_id,scope,window_start,request_count) values(?,'GROUP_JOIN',?,1) on conflict(member_id,scope,window_start) do update set request_count=auth_request_window.request_count+1 returning request_count",member,windowStart);
    }


}
