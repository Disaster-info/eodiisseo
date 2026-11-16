package com.disasterinfo.eodiisseo.location.repository;

import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository @Profile("!test")
public class LocationRepository {
    private final OwnedDatabase db;
    public LocationRepository(OwnedDatabase db) { this.db=db; }
    public void lock(UUID member) { db.lockMember(member); }
    public boolean consented(UUID member) {
        return db.count("select count(*) from member_location_consent where member_id=? and consented", member)==1;
    }
    public void consent(UUID member, boolean consented, Instant now) {
        db.update("insert into member_location_consent values(?,?,?) on conflict(member_id) do update set consented=excluded.consented,changed_at=excluded.changed_at", member,consented,now);
        if (!consented) {
            db.update("delete from member_location where member_id=?",member);
            db.update("update group_member set location_sharing_enabled=false where member_id=?",member);
        }
    }
    public void save(UUID member, double latitude, double longitude, Instant now) {
        db.update("insert into member_location(member_id,location_mode,point,updated_at) values(?,'GPS',ST_SetSRID(ST_MakePoint(?,?),4326)::geography,?) on conflict(member_id) do update set point=excluded.point,updated_at=excluded.updated_at",member,longitude,latitude,now);
    }
}
