package com.disasterinfo.eodiisseo.disaster;
import com.disasterinfo.eodiisseo.disaster.service.DisasterAreaNormalizer;
import com.disasterinfo.eodiisseo.disaster.service.DisasterAreaNormalizer.*;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DisasterAreaNormalizerTest {
    final Map<String,Map<String,Object>> catalog=new LinkedHashMap<>();DisasterAreaNormalizer normalizer;
    void area(String code,String name,String level,String parent){var row=new HashMap<String,Object>();row.put("area_code",code);row.put("name",name);row.put("level",level);row.put("parent_area_code",parent);row.put("active",true);catalog.put(code,row);}
    @BeforeEach void setup(){
        area("GG","경기도","SIDO",null);area("UJ","의정부시","SIGUNGU","GG");area("HW","호원동","EUPMYEONDONG","UJ");area("YJ","양주시","SIGUNGU","GG");
        area("SW","수원시","SIGUNGU","GG");area("YT","영통구","SIGUNGU","SW");area("SL","서울특별시","SIDO",null);area("SJ","중구","SIGUNGU","SL");area("IC","인천광역시","SIDO",null);area("IJ","중구","SIGUNGU","IC");
        var db=mock(OwnedDatabase.class);
        when(db.one(anyString(),anyString())).thenAnswer(c->catalog.get(c.<String>getArgument(1)));
        when(db.rows(anyString(),anyString())).thenAnswer(c->catalog.values().stream().filter(n->n.get("name").equals(c.getArgument(1))&&n.get("level").equals("SIDO")).toList());
        when(db.rows(anyString(),anyString(),anyString())).thenAnswer(c->catalog.values().stream().filter(n->n.get("name").equals(c.getArgument(1))&&Objects.equals(n.get("parent_area_code"),c.getArgument(2))).toList());
        when(db.count(anyString(),anyString())).thenAnswer(c->catalog.values().stream().filter(n->Objects.equals(n.get("parent_area_code"),c.getArgument(1))&&n.get("level").equals("SIGUNGU")&&Boolean.TRUE.equals(n.get("active"))).count());
        normalizer=new DisasterAreaNormalizer(db);
    }
    @ParameterizedTest @ValueSource(strings={"경기도 의정부시","경기도 의정부시 호원동","경기도 의정부시 의정부1동"})
    void fullOfficialHierarchyResolvesWithOrWithoutDongCatalog(String address){assertThat(normalizer.byAddress(address,Meaning.SOURCE_ADDRESS)).get().extracting(Match::sigungu).isEqualTo("UJ");}
    @Test void nestedCityAndGuChooseTheExactLowestSigungu(){assertThat(normalizer.byAddress("경기도 수원시 영통구 원천동",Meaning.OCCURRENCE)).get().extracting(Match::sigungu).isEqualTo("YT");}
    @Test void sameGuNameIsDisambiguatedByItsOfficialParent(){assertThat(normalizer.byAddress("서울특별시 중구",Meaning.OFFICIAL_IMPACT)).get().extracting(Match::sigungu).isEqualTo("SJ");assertThat(normalizer.byAddress("인천광역시 중구",Meaning.OFFICIAL_IMPACT)).get().extracting(Match::sigungu).isEqualTo("IJ");}
    @ParameterizedTest @ValueSource(strings={"호원동","중구","전국","해상","경기도 의정부시 외 2개 지역","경기도 의정부시, 양주시","경기도 양주시 호원동 의정부시","경기도 의정부시 타지역"})
    void incompleteOrAmbiguousTextNeverProducesACompleteRegion(String address){assertThat(normalizer.byAddress(address,Meaning.OFFICIAL_IMPACT)).isEmpty();}
    @Test void exactCodeWinsAndInvalidCodeDoesNotFallBackToText(){assertThat(normalizer.resolve("HW","인천광역시 중구",Meaning.RECIPIENT)).get().satisfies(m->{assertThat(m.sigungu()).isEqualTo("UJ");assertThat(m.meaning()).isEqualTo(Meaning.RECIPIENT);assertThat(m.catalogPath()).containsExactly("HW","UJ","GG");});assertThat(normalizer.resolve("MISSING","경기도 의정부시",Meaning.OFFICIAL_IMPACT)).isEmpty();}
    @Test void inactiveMissingParentCycleAndIncorrectLevelAreRejected(){catalog.get("UJ").put("active",false);assertThat(normalizer.byCode("HW",Meaning.OFFICIAL_IMPACT)).isEmpty();catalog.get("UJ").put("active",true);catalog.get("GG").put("parent_area_code","HW");assertThat(normalizer.byCode("HW",Meaning.OFFICIAL_IMPACT)).isEmpty();catalog.get("GG").put("parent_area_code",null);catalog.get("UJ").put("parent_area_code","MISSING");assertThat(normalizer.byCode("UJ",Meaning.OFFICIAL_IMPACT)).isEmpty();}
    @Test void allMultiRegionInputsAndTheirIndividualProvenanceAreRetained(){var result=normalizer.codes(Set.of("HW","UJ","YJ"),Meaning.OFFICIAL_IMPACT).orElseThrow();assertThat(result).hasSize(3);assertThat(result).extracting(Match::input).containsExactly("HW","UJ","YJ");assertThat(result).extracting(Match::sigungu).containsExactly("UJ","UJ","YJ");assertThat(normalizer.codes(Set.of("HW","MISSING"),Meaning.OFFICIAL_IMPACT)).isEmpty();}
    @Test void sourceAddressAndOccurrenceNeverBecomeOfficialImpact(){for(var meaning:List.of(Meaning.SOURCE_ADDRESS,Meaning.OCCURRENCE,Meaning.RECIPIENT,Meaning.UNKNOWN))assertThat(normalizer.byAddress("경기도 의정부시 호원동",meaning)).get().extracting(Match::meaning).isEqualTo(meaning);}
    @Test void knownInactiveDongDoesNotUseTheMissingDongException(){catalog.get("HW").put("active",false);assertThat(normalizer.byAddress("경기도 의정부시 호원동",Meaning.OFFICIAL_IMPACT)).isEmpty();}
    @Test void explicitFullAddressListIsAllOrNothingAndKeepsEachOriginalAddress(){var input=List.of("경기도 의정부시 호원동","인천광역시 중구");var result=normalizer.addresses(input,Meaning.SOURCE_ADDRESS).orElseThrow();assertThat(result).extracting(Match::input).containsExactlyElementsOf(input);assertThat(result).extracting(Match::sigungu).containsExactly("UJ","IJ");assertThat(normalizer.addresses(List.of(input.getFirst(),"중구 외 2개 지역"),Meaning.OFFICIAL_IMPACT)).isEmpty();}
    @Test void omittedGuInAFullAddressCannotBroadenADongToTheWholeCity(){assertThat(normalizer.byAddress("경기도 수원시 원천동",Meaning.OFFICIAL_IMPACT)).isEmpty();}
    @Test void aDongCatalogSkippingAnAvailableGuMustRemainUnknown(){area("SKIP_GU","원천동","EUPMYEONDONG","SW");assertThat(normalizer.byCode("SKIP_GU",Meaning.OFFICIAL_IMPACT)).isEmpty();}
}