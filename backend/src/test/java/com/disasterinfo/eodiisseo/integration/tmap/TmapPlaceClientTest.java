package com.disasterinfo.eodiisseo.integration.tmap;

import com.disasterinfo.eodiisseo.common.exception.*;
import java.time.Duration;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TmapPlaceClientTest {
    private MockRestServiceServer server;
    private TmapClient client;
    @BeforeEach void setup() {
        var builder = RestClient.builder().baseUrl("https://example.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TmapClient(properties("server-only"));
        ReflectionTestUtils.setField(client,"restClient",builder.build());
    }
    @Test void searchUsesServerKeyAndEncodesQueryValues() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("searchKeyword=a%26b%20%23")))
                .andExpect(header("appKey","server-only")).andExpect(header("Accept","application/json"))
                .andRespond(withNoContent());
        assertThat(client.searchPois("a&b #")).isNull();
        server.verify();
    }
    @Test void geocodeAndReverseUseExpectedContracts() {
        server.expect(requestTo("https://example.test/tmap/geo/fullAddrGeo?version=1&fullAddr=Seoul&coordType=WGS84GEO"))
                .andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://example.test/tmap/geo/reversegeocoding?version=1&lat=37.0&lon=127.0&coordType=WGS84GEO&addressType=A10"))
                .andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        assertThat(client.geocode("Seoul")).isNotNull();
        assertThat(client.reverseGeocode(37,127)).isNotNull();
        server.verify();
    }
    @Test void missingKeyIs503WithoutRequest() {
        client = new TmapClient(properties(""));
        assertError(() -> client.searchPois("역"),ErrorCode.PLACE_API_NOT_CONFIGURED);
        assertError(() -> client.geocode("서울"),ErrorCode.PLACE_API_NOT_CONFIGURED);
        assertError(() -> client.reverseGeocode(37,127),ErrorCode.PLACE_API_NOT_CONFIGURED);
        assertThat(ErrorCode.PLACE_API_NOT_CONFIGURED.getStatus().value()).isEqualTo(503);
    }
    @Test void providerErrorDoesNotLeakBodyOrKey() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("secret provider detail"));
        assertError(() -> client.searchPois("역"),ErrorCode.EXTERNAL_API_ERROR);
    }
    @Test void malformedJsonIs502() {
        server.expect(anything()).andRespond(withSuccess("not json",MediaType.APPLICATION_JSON));
        assertError(() -> client.searchPois("역"),ErrorCode.EXTERNAL_API_ERROR);
    }
    @Test void timeoutIs504() {
        server.expect(anything()).andRespond(withException(new SocketTimeoutException("secret")));
        assertError(() -> client.searchPois("역"),ErrorCode.EXTERNAL_API_TIMEOUT);
    }
    private static void assertError(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(BusinessException.class).hasMessage(code.getMessage())
                .hasNoCause().extracting(e -> ((BusinessException)e).getErrorCode()).isEqualTo(code);
    }
    private static TmapProperties properties(String key) {
        return new TmapProperties("https://example.test",key,Duration.ofSeconds(3),Duration.ofSeconds(5));
    }
}
