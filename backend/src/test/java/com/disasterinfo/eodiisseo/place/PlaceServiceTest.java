package com.disasterinfo.eodiisseo.place;

import com.disasterinfo.eodiisseo.common.exception.BusinessException;
import com.disasterinfo.eodiisseo.common.exception.ErrorCode;
import com.disasterinfo.eodiisseo.common.response.GeoPoint;
import com.disasterinfo.eodiisseo.integration.tmap.TmapClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaceServiceTest {
    private final TmapClient client = mock(TmapClient.class);
    private final PlaceService service = new PlaceService(client);
    private final ObjectMapper json = new ObjectMapper();

    @Test void searchDropsInvalidCoordinatesAndPreservesAddressParts() {
        when(client.searchPois("역")).thenReturn(json.readTree("""
            {"searchPoiInfo":{"pois":{"poi":[
              {"id":"1","name":"역","upperAddrName":"경기","middleAddrName":"수원시 팔달구","lowerAddrName":"매산로","frontLat":"37.2","frontLon":"127"},
              {"frontLat":"","frontLon":"127"},{"frontLat":"NaN","frontLon":"127"},
              {"frontLat":"91","frontLon":"127"},{"frontLat":"37","frontLon":"181"},
              {"frontLat":"0","frontLon":"0"},{}]}}}
            """));
        assertThat(service.search("역")).containsExactly(new PlaceService.Poi("1", "역", "경기", "수원시 팔달구", "매산로", 37.2, 127));
    }
    @Test void emptySearchReturnsEmptyList() {
        assertThat(service.search("없음")).isEmpty();
        when(client.searchPois("없음")).thenReturn(json.readTree("{}"));
        assertThat(service.search("없음")).isEmpty();
    }
    @Test void geocodeUsesRoadCoordinatesWhenOldCoordinatesAreEmpty() {
        when(client.geocode("서울")).thenReturn(json.readTree("""
            {"coordinateInfo":{"coordinate":[{"lat":"","lon":"","newLat":"37.5","newLon":"127"}]}}
            """));
        assertThat(service.geocode("서울")).isEqualTo(new GeoPoint(37.5,127));
    }
    @Test void missingGeocodeAndRegionAreSafeErrors() {
        assertThatThrownBy(() -> service.geocode("없음")).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException)e).getErrorCode()).isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
        assertThatThrownBy(() -> service.reverseGeocode(37,127)).isInstanceOf(BusinessException.class);
    }
    @Test void reverseGeocodePreservesLegalCode() {
        when(client.reverseGeocode(37,127)).thenReturn(json.readTree("""
            {"addressInfo":{"city_do":"경기도","gu_gun":"수원시 팔달구","legalDongCode":"4111510100"}}
            """));
        assertThat(service.reverseGeocode(37,127)).isEqualTo(new PlaceService.Region("경기도","수원시 팔달구","4111510100"));
    }
}
