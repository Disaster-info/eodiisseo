package com.disasterinfo.eodiisseo.checklist.repository;

import java.util.*;
import java.time.Instant;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;

/** SQL and bind parameters for the checklist feature. Transactions are owned by the service. */
@Repository @Profile("!test")
public class ChecklistRepository {
    private final OwnedDatabase db;
    public ChecklistRepository(OwnedDatabase db) { this.db=db; }

    public Map<String,Object> findActiveTemplate(String type) {
        return db.one("select t.*,d.code,r.agency_name,r.title as reference,r.source_url from checklist_template t join disaster_type d using(disaster_type_id) join official_resource r using(resource_id) where d.code=? and t.active=true",type);
    }

    public Map<String,Object> findActiveItemForShare(UUID item, UUID template) {
        return db.one("select i.item_id from checklist_item i join checklist_template t using(template_id) where i.item_id=? and t.template_id=? and i.active=true and t.active=true for share of t,i",item,template);
    }

    public int markCompleted(UUID member, UUID item, Instant now) {
        return db.update("insert into member_check_item(member_id,item_id,completed_at) values(?,?,?) on conflict(member_id,item_id) do nothing",member,item,now);
    }

    public int clearCompleted(UUID member, UUID item) {
        return db.update("delete from member_check_item where member_id=? and item_id=?",member,item);
    }

    public List<Map<String,Object>> listMemberItems(UUID member, UUID template) {
        return db.rows("select i.item_id as \"itemId\",i.content,(c.item_id is not null) as completed from checklist_item i left join member_check_item c on c.item_id=i.item_id and c.member_id=? where i.template_id=? and i.active=true order by i.item_order,i.item_id",member,template);
    }

    public boolean tableExists(String table) { return db.tableExists(table); }
    public void lockMember(UUID member) { db.lockMember(member); }
}
