package com.disasterinfo.eodiisseo.place;

import com.disasterinfo.eodiisseo.common.exception.BusinessException;
import com.disasterinfo.eodiisseo.common.exception.ErrorCode;
import com.disasterinfo.eodiisseo.common.response.GeoPoint;
import com.disasterinfo.eodiisseo.integration.tmap.TmapClient;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class PlaceService {
    private final TmapClient client;
    public PlaceService(TmapClient client) { this.client = client; }

    public record Poi(String id, String name, String upperAddrName, String middleAddrName,
                      String lowerAddrName, double latitude, double longitude) {}
    public record Region(String cityDo, String guGun, String legalDongCode) {}

    public List<Poi> search(String keyword) {
        JsonNode root = client.searchPois(keyword);
        if (root == null) return List.of();
        JsonNode pois = root.path("searchPoiInfo").path("pois").path("poi");
        if (pois.isMissingNode() || pois.isNull()) return List.of();
        if (!pois.isArray()) throw invalidResponse();
        List<Poi> result = new ArrayList<>();
        for (JsonNode p : pois) {
            GeoPoint point = point(text(p, "frontLat"), text(p, "frontLon"));
            if (point != null) result.add(new Poi(text(p, "id"), text(p, "name"), text(p, "upperAddrName"),
                    text(p, "middleAddrName"), text(p, "lowerAddrName"), point.latitude(), point.longitude()));
        }
        return result;
    }

    public GeoPoint geocode(String address) {
        JsonNode root = client.geocode(address);
        if (root == null) throw invalidResponse();
        JsonNode c = root.path("coordinateInfo").path("coordinate").path(0);
        String lat = text(c, "lat"), lon = text(c, "lon");
        GeoPoint point = point(lat.isEmpty() ? text(c, "newLat") : lat, lon.isEmpty() ? text(c, "newLon") : lon);
        if (point == null) throw invalidResponse();
        return point;
    }

    public Region reverseGeocode(double latitude, double longitude) {
        JsonNode root = client.reverseGeocode(latitude, longitude);
        if (root == null) throw invalidResponse();
        JsonNode info = root.path("addressInfo");
        if (text(info, "city_do").isBlank()) throw invalidResponse();
        return new Region(text(info, "city_do"), text(info, "gu_gun"), text(info, "legalDongCode"));
    }

    private static String text(JsonNode node, String key) { return node.path(key).asText(""); }
    private static GeoPoint point(String lat, String lon) {
        try {
            double latitude = Double.parseDouble(lat), longitude = Double.parseDouble(lon);
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || Math.abs(latitude) > 90
                    || Math.abs(longitude) > 180 || (latitude == 0 && longitude == 0)) return null;
            return new GeoPoint(latitude, longitude);
        } catch (NumberFormatException e) { return null; }
    }
    private static BusinessException invalidResponse() { return new BusinessException(ErrorCode.EXTERNAL_API_ERROR); }
}
