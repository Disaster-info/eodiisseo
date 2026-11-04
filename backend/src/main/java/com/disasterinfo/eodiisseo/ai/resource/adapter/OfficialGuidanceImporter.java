package com.disasterinfo.eodiisseo.ai.resource.adapter;

import com.disasterinfo.eodiisseo.ai.resource.port.OfficialResourceStore;
import com.disasterinfo.eodiisseo.ai.resource.entity.OfficialResourceDocument;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import com.disasterinfo.eodiisseo.auth.service.*;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.disaster.collection.OfficialHttp;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import java.io.*;
import javax.swing.text.*;
import javax.swing.text.html.*;
import javax.swing.text.html.parser.ParserDelegator;

/** Explicit OPS import tool. No endpoint, startup import, credentials, approval or external AI transmission. */
@Service @Profile("!test")
public class OfficialGuidanceImporter {
    private static final Set<String> HOSTS=Set.of("www.mois.go.kr","mois.go.kr","www.safekorea.go.kr","www.safety24.go.kr");
    private final OwnedDatabase db;private final OfficialResourceStore store;private final SessionAccessService sessions;
    private final AuthService auth;private final Clock clock;private final TransactionTemplate tx;
    public OfficialGuidanceImporter(OwnedDatabase db,OfficialResourceStore store,SessionAccessService sessions,AuthService auth,
            Clock clock,PlatformTransactionManager manager){
        this.db=db;this.store=store;this.sessions=sessions;this.auth=auth;this.clock=clock;tx=new TransactionTemplate(manager);
    }
    private void requireOps(MemberPrincipal actor){
        if(actor==null)throw ApiProblem.unauthorized();sessions.requireActiveSession(actor.memberId(),actor.sessionId());
        if(!((List<?>)auth.me(actor).get("roles")).contains("OPS"))throw ApiProblem.forbidden();
    }
    public static URI officialUri(String source){
        URI uri;
        try{uri=URI.create(source);}catch(RuntimeException e){throw new IllegalArgumentException("Invalid official URL");}
        if(!"https".equals(uri.getScheme())||!HOSTS.contains(uri.getHost())||uri.getRawUserInfo()!=null||
            uri.getPort()!=-1||uri.getFragment()!=null||uri.getPath()==null||uri.getPath().isBlank()
            ||source.length()>2000||source.matches("(?i).*(servicekey|token|password|authkey)=.*"))
            throw new IllegalArgumentException("A public official HTTPS document is required");
        return uri;
    }
    /** Caller must explicitly attest a development network environment; acquisition is never implicit. */
    public OfficialResourceDocument fetch(MemberPrincipal actor,String url,String title,String bodyElementId,Instant expires,String environment){
        requireOps(actor);
        if(!"development".equals(environment))throw new IllegalArgumentException("Explicit development environment required");
        URI uri=officialUri(url);
        try(var http=new OfficialHttp()){
            var response=http.get(uri,Duration.ofSeconds(15));
            if(response.status()!=200)throw new IllegalStateException("Official document unavailable");
            return publish(actor,url,title,bodyElementId,response.body(),expires);
        }
    }
    /** Also supports audited offline HTML exports. Source bytes are preserved; reviewer checks authenticity before approval. */
    public OfficialResourceDocument publish(MemberPrincipal actor,String url,String title,String bodyElementId,byte[] raw,Instant expires){
        requireOps(actor);officialUri(url);
        Instant now=clock.instant();
        if(expires==null||!expires.isAfter(now)||expires.isAfter(now.plus(Duration.ofDays(90))))throw new IllegalArgumentException("Finite review expiry required");
        String body=extract(raw,bodyElementId),hash=sha(raw);
        return tx.execute(status->{
            final OfficialResourceDocument[] result={null};
            sessions.runWithActiveSession(actor.memberId(),actor.sessionId(),()->{
                requireOps(actor);
                db.rows("select pg_advisory_xact_lock(hashtextextended(?,0))","guidance:"+url);
                var existing=db.one("select resource_id from owned_resource_import where source_url=? and content_sha256=?",url,hash);
                if(existing!=null){result[0]=store.findById((UUID)existing.get("resource_id")).orElseThrow();return;}
                // Lock document rows in the same order used by human review; a stale review cannot race publication.
                var prior=db.rows("select r.resource_id from official_resource r where r.source_url=? order by r.resource_id for update of r",url);
                for(var row:prior){
                    db.update("update ai_resource_review set revoked_at=? where resource_id=?",now,row.get("resource_id"));
                    db.update("update owned_resource_import set superseded_at=coalesce(superseded_at,?) where resource_id=?",now,row.get("resource_id"));
                }
                var doc=new OfficialResourceDocument(UUID.randomUUID(),"행정안전부",title,url,"sha256:"+hash,body,now);
                result[0]=store.append(doc);
                db.update("insert into owned_resource_import(resource_id,source_url,content_sha256,raw_body,media_type,fetched_at,expires_at) values(?,?,?,?,?,?,?)",
                    doc.getResourceId(),url,hash,raw,"text/html; charset=UTF-8",now,expires);
            });
            return result[0];
        });
    }
    /** Expire/revoke and erase acquisition bytes; cited immutable document text is retained under existing citation policy. */
    public int purgeExpired(MemberPrincipal actor){
        requireOps(actor);
        return tx.execute(status->{
            final int[] count={0};
            sessions.runWithActiveSession(actor.memberId(),actor.sessionId(),()->{
                requireOps(actor);
                db.update("update ai_resource_review set revoked_at=coalesce(revoked_at,?) where resource_id in (select resource_id from owned_resource_import where expires_at<=?)",clock.instant(),clock.instant());
                count[0]=db.update("update owned_resource_import set raw_body=? where expires_at<=? and octet_length(raw_body)>0",new byte[0],clock.instant());
            });return count[0];
        });
    }
    public static String extract(byte[] raw,String elementId){
        if(raw==null||raw.length==0||raw.length>2_097_152||elementId==null||!elementId.matches("[A-Za-z][A-Za-z0-9_-]{0,99}"))
            throw new IllegalArgumentException("A bounded UTF-8 HTML body and explicit element ID are required");
        try{
            String html=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(raw)).toString();
            var paragraphs=new ArrayList<String>();var line=new StringBuilder();
            int[] depth={0},ignored={0},matches={0};
            var callback=new HTMLEditorKit.ParserCallback(){
                void paragraph(){String text=line.toString().replaceAll("[\\p{Zs}\\t\\r\\n]+"," ").strip();if(!text.isEmpty())paragraphs.add(text);line.setLength(0);}
                public void handleStartTag(HTML.Tag tag,MutableAttributeSet attributes,int position){
                    if(depth[0]>0)depth[0]++;
                    else if(elementId.equals(attributes.getAttribute(HTML.Attribute.ID))){depth[0]=1;matches[0]++;}
                    if(depth[0]>0&&(tag==HTML.Tag.SCRIPT||tag==HTML.Tag.STYLE))ignored[0]++;
                    if(depth[0]>0&&Set.of(HTML.Tag.P,HTML.Tag.LI,HTML.Tag.H1,HTML.Tag.H2,HTML.Tag.H3,HTML.Tag.DIV).contains(tag))paragraph();
                }
                public void handleEndTag(HTML.Tag tag,int position){
                    if(depth[0]>0){
                        if(Set.of(HTML.Tag.P,HTML.Tag.LI,HTML.Tag.DIV).contains(tag))paragraph();
                        if((tag==HTML.Tag.SCRIPT||tag==HTML.Tag.STYLE)&&ignored[0]>0)ignored[0]--;
                        if(--depth[0]==0)paragraph();
                    }
                }
                public void handleSimpleTag(HTML.Tag tag,MutableAttributeSet attributes,int pos){
                    if(depth[0]>0&&tag==HTML.Tag.BR)paragraph();
                    // Official poster pages provide accessible original text in ALT. No OCR or remote image fetch.
                    if(depth[0]>0&&ignored[0]==0&&tag==HTML.Tag.IMG){
                        Object alt=attributes.getAttribute(HTML.Attribute.ALT);
                        if(alt instanceof String text&&!text.isBlank()){paragraph();line.append(text);paragraph();}
                    }
                }
                public void handleText(char[] text,int position){if(depth[0]>0&&ignored[0]==0)line.append(text).append(' ');}
            };
            new ParserDelegator().parse(new StringReader(html),callback,true);
            String body=String.join("\n\n",paragraphs);
            if(matches[0]!=1||body.isBlank()||body.length()>100000)throw new IllegalArgumentException("Official body extraction failed");
            return body;
        }catch(IOException e){throw new IllegalArgumentException("Official HTML must be valid UTF-8");}
    }
    private static String sha(byte[] raw){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw));}catch(Exception e){throw new IllegalStateException();}}
}
