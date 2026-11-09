package com.disasterinfo.eodiisseo.disaster.service;

import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import java.util.*;

/** Catalog normalization only: never grants impact, incident, recipient or ownership evidence. */
public final class DisasterAreaNormalizer {
    private final OwnedDatabase db;
    public DisasterAreaNormalizer(OwnedDatabase db){this.db=Objects.requireNonNull(db);}
    public enum Meaning { OFFICIAL_IMPACT, RECIPIENT, OCCURRENCE, SOURCE_ADDRESS, UNKNOWN }
    public record Match(String input,String sigungu,List<String> catalogPath,Meaning meaning) {
        public Match {catalogPath=List.copyOf(catalogPath);}
    }
    private Map<String,Object> node(String code){return db.one("select area_code,name,level,parent_area_code,active from admin_area where area_code=?",code);}
    private boolean hasSigunguChildren(String code){return db.count("select count(*) from admin_area where parent_area_code=? and level='SIGUNGU' and active",code)>0;}
    /** Exact codes take precedence. An invalid code must never fall back to a plausible address. */
    public Optional<Match> resolve(String code,String address,Meaning meaning){
        if(meaning==null)return Optional.empty();
        return code!=null?byCode(code,meaning):byAddress(address,meaning);
    }
    public Optional<Match> byCode(String code,Meaning meaning){
        if(code==null||!code.matches("[A-Za-z0-9_-]{1,20}")||meaning==null)return Optional.empty();
        var path=new ArrayList<String>();var seen=new HashSet<String>();String current=code,target=null;String previous=null;
        for(int depth=0;depth<16&&current!=null;depth++){
            if(!seen.add(current))return Optional.empty();var row=node(current);
            if(row==null||!Boolean.TRUE.equals(row.get("active")))return Optional.empty();
            String level=(String)row.get("level");
            if(!Set.of("SIDO","SIGUNGU","EUPMYEONDONG").contains(level))return Optional.empty();
            if(previous!=null&&(previous.equals("SIDO")||previous.equals("EUPMYEONDONG")&&level.equals("EUPMYEONDONG")||previous.equals("SIGUNGU")&&level.equals("EUPMYEONDONG")))return Optional.empty();
            if(target==null&&level.equals("SIGUNGU"))target=current;
            path.add(current);String parent=(String)row.get("parent_area_code");
            // Standalone SIGUNGU catalogs already accepted by the team remain usable; a specified parent must resolve.
            if(parent==null){if(target==null||level.equals("EUPMYEONDONG")||!code.equals(target)&&hasSigunguChildren(target))return Optional.empty();return Optional.of(new Match(code,target,path,meaning));}
            previous=level;current=parent;
        }
        return Optional.empty();
    }
    /** Closed full hierarchy grammar, exact catalog names; no substring, prefix-code, distance or alias matching. */
    public Optional<Match> byAddress(String address,Meaning meaning){
        if(address==null||meaning==null||address.length()>300||!address.equals(address.strip())
                ||java.util.regex.Pattern.compile("(^|\\s)외\\s*([0-9]+|지역)").matcher(address).find()||address.contains(",")||address.contains(";")||address.contains("·")||address.contains("전국")||address.contains("해상"))return Optional.empty();
        String[] words=address.split("\\s+");if(words.length<2||words.length>6)return Optional.empty();
        var roots=db.rows("select area_code,name,level,parent_area_code,active from admin_area where name=? and level='SIDO'",words[0]);
        if(roots.size()!=1||!Boolean.TRUE.equals(roots.getFirst().get("active"))||roots.getFirst().get("parent_area_code")!=null)return Optional.empty();
        String parent=(String)roots.getFirst().get("area_code"),target=null;
        for(int i=1;i<words.length;i++){
            var children=db.rows("select area_code,name,level,parent_area_code,active from admin_area where name=? and parent_area_code=?",words[i],parent);
            if(children.size()>1)return Optional.empty();
            if(children.isEmpty()){
                // Missing dong catalog is allowed only as one final explicit suffix of an already verified full SIGUNGU path.
                if(i!=words.length-1||target==null||!words[i].matches("[가-힣0-9]+[읍면동]")||hasSigunguChildren(target))return Optional.empty();
                var known=byCode(target,meaning);return known.map(m->new Match(address,m.sigungu(),m.catalogPath(),meaning));
            }
            var child=children.getFirst();if(!Boolean.TRUE.equals(child.get("active")))return Optional.empty();
            String level=(String)child.get("level");
            if(level.equals("SIGUNGU")){if(target!=null&&!words[i].endsWith("구"))return Optional.empty();target=(String)child.get("area_code");}
            else if(!level.equals("EUPMYEONDONG")||target==null||i!=words.length-1)return Optional.empty();
            parent=(String)child.get("area_code");
        }
        if(target==null)return Optional.empty();var result=byCode(parent,meaning);
        return result.map(m->new Match(address,m.sigungu(),m.catalogPath(),meaning));
    }
    /** An explicit complete list is required; any unresolved member rejects the whole list. */
    public Optional<List<Match>> codes(Set<String> codes,Meaning meaning){
        if(codes==null||codes.isEmpty()||codes.size()>1000||codes.stream().anyMatch(Objects::isNull))return Optional.empty();var result=new ArrayList<Match>();
        for(String code:new TreeSet<>(codes)){var resolved=byCode(code,meaning);if(resolved.isEmpty())return Optional.empty();result.add(resolved.get());}
        return Optional.of(List.copyOf(result));
    }
    /** Already separated, fully qualified official addresses only. Delimiter interpretation belongs to the provider contract. */
    public Optional<List<Match>> addresses(List<String> addresses,Meaning meaning){
        if(addresses==null||addresses.isEmpty()||addresses.size()>1000)return Optional.empty();var result=new ArrayList<Match>();
        for(String address:addresses){var resolved=byAddress(address,meaning);if(resolved.isEmpty())return Optional.empty();result.add(resolved.get());}
        return Optional.of(List.copyOf(result));
    }
}