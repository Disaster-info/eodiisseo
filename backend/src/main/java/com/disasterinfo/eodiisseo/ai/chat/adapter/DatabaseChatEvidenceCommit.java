package com.disasterinfo.eodiisseo.ai.chat.adapter;
import com.disasterinfo.eodiisseo.ai.chat.*;
import com.disasterinfo.eodiisseo.ai.chat.port.*;
import com.disasterinfo.eodiisseo.ai.resource.*;
import com.disasterinfo.eodiisseo.ai.resource.port.*;
import com.disasterinfo.eodiisseo.ai.resource.entity.OfficialResourceDocument;
import com.disasterinfo.eodiisseo.ai.resource.vector.RagChunkGroup;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Clock;
import java.util.*;
import java.util.function.Consumer;

/** Own AI rows only. Session/member transaction is acquired first by the existing authentication bridge. */
@Component @Profile("!test")
public class DatabaseChatEvidenceCommit implements ChatEvidenceCommitPort {
    private final JdbcTemplate sql;private final OfficialResourceStore documents;private final ResourceApprovalPort approvals;private final Clock clock;
    public DatabaseChatEvidenceCommit(JdbcTemplate sql,OfficialResourceStore documents,ResourceApprovalPort approvals,Clock clock){this.sql=sql;this.documents=documents;this.approvals=approvals;this.clock=clock;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void withCurrentEvidence(List<ChatOfficialResourceSearchPort.OfficialResource> expected,Consumer<Boolean> persist){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new ChatException(ChatException.Reason.PERSISTENCE_FAILURE,"An authenticated completion transaction is required.");
        if(expected.isEmpty()||expected.size()>3)throw new ChatException(ChatException.Reason.PERSISTENCE_FAILURE,"A bounded evidence snapshot is required.");
        try{
            var ids=expected.stream().map(ChatOfficialResourceSearchPort.OfficialResource::resourceId).distinct().sorted().toList();
            for(var id:ids)sql.queryForList("select resource_id from official_resource where resource_id=? for update",id);
            for(var id:ids)sql.queryForList("select resource_id from ai_resource_review where resource_id=? for update",id);
            for(var id:ids)sql.queryForList("select resource_id from owned_resource_import where resource_id=? for update",id);
            var current=new ArrayList<OfficialResourceDocument>();for(var e:expected)documents.findById(e.resourceId()).ifPresent(current::add);
            OfficialResourceStore snapshot=new OfficialResourceStore(){
                public boolean isAvailable(){return documents.isAvailable();}
                public Optional<OfficialResourceDocument> findById(UUID id){return documents.findById(id);}
                public OfficialResourceDocument append(OfficialResourceDocument d){throw new UnsupportedOperationException();}
                public List<OfficialResourceDocument> searchCandidates(List<String> terms,int limit){return current;}
            };
            ResourceParagraphSearch exact=new ResourceParagraphSearch(){
                public Optional<Query> prepare(String ignored){return Optional.of(new Query("COMMIT",List.of("COMMIT"),List.of()));}
                public List<String> select(Query q,OfficialResourceDocument d){return expected.stream().filter(e->e.resourceId().equals(d.getResourceId())).findFirst().map(e->Arrays.asList(RagChunkGroup.BOUNDARY.split(e.content()))).orElse(List.of());}
            };
            var validator=new ResourceService(snapshot,exact,Optional.of(approvals),clock);
            var checked=validator.search("COMMIT");boolean valid=checked.sufficientEvidence()&&checked.resources().equals(expected);
            persist.accept(valid);
            if(valid){var after=validator.search("COMMIT");if(!after.sufficientEvidence()||!after.resources().equals(expected))throw new ChatException(ChatException.Reason.PERSISTENCE_FAILURE,"Evidence expired before transaction completion.");}
        }catch(ChatException failure){throw failure;}
        catch(RuntimeException failure){throw new ChatException(ChatException.Reason.PERSISTENCE_FAILURE,"Evidence could not be verified at persistence.");}
    }
}