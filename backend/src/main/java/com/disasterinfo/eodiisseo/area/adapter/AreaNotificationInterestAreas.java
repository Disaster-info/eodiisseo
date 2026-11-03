package com.disasterinfo.eodiisseo.area.adapter;

import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.*;
import com.disasterinfo.eodiisseo.notification.NotificationException;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

public class AreaNotificationInterestAreas implements InterestAreas {
    private final OwnedDatabase db;
    public AreaNotificationInterestAreas(OwnedDatabase db){this.db=db;}
    public List<InterestArea> findOwned(UUID member){return db.rows("select i.interest_area_id,i.notify_enabled from interest_area i join member m using(member_id) where i.member_id=? and m.status='ACTIVE' order by i.interest_area_id",member).stream().map(r->new InterestArea((UUID)r.get("interest_area_id"),(Boolean)r.get("notify_enabled"))).toList();}
    @Transactional public void replaceSelection(UUID member,Set<UUID> selected){
        db.lockMember(member);if(selected==null)throw new NotificationException(NotificationException.Reason.INVALID_REQUEST);
        var owned=new HashSet<UUID>();findOwned(member).forEach(a->owned.add(a.id()));
        if(!owned.containsAll(selected))throw new NotificationException(NotificationException.Reason.FORBIDDEN);
        db.update("update interest_area set notify_enabled=false where member_id=?",member);
        for(UUID id:selected)db.update("update interest_area set notify_enabled=true where member_id=? and interest_area_id=?",member,id);
    }
    public boolean isRelated(UUID disaster,UUID area){
        // Match documented administrative ancestry, never recipient names or epicenter proximity.
        return db.count("with recursive region as (select a.area_code,a.parent_area_code from interest_area i join member m using(member_id) join admin_area a using(area_code) where i.interest_area_id=? and m.status='ACTIVE' and a.active union select a.area_code,a.parent_area_code from admin_area a join region r on a.area_code=r.parent_area_code where a.active) select count(*) from disaster_area da join disaster d using(disaster_id) where da.disaster_id=? and d.data_origin='OFFICIAL' and da.area_role='IMPACT' and da.area_code in (select area_code from region) and da.basis=case when exists(select 1 from disaster_area x where x.disaster_id=da.disaster_id and x.area_role='IMPACT' and x.basis='OFFICIAL') then 'OFFICIAL' else 'SOURCE_ADDRESS' end",area,disaster)>0;
    }
}
