package com.disasterinfo.eodiisseo.place;

import com.disasterinfo.eodiisseo.common.response.GeoPoint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/places")
public class PlaceController {
    private final PlaceService service;
    public PlaceController(PlaceService service) { this.service = service; }

    public record SearchRequest(@NotBlank @Size(min = 1, max = 100) String keyword) {}
    public record GeocodeRequest(@NotBlank @Size(min = 1, max = 300) String address) {}
    public record ReverseRequest(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) {}

    @GetMapping("/search")
    public List<PlaceService.Poi> search(@Valid @ModelAttribute SearchRequest request) {
        return service.search(request.keyword());
    }
    @GetMapping("/geocode")
    public GeoPoint geocode(@Valid @ModelAttribute GeocodeRequest request) {
        return service.geocode(request.address());
    }
    @GetMapping("/reverse-geocode")
    public PlaceService.Region reverseGeocode(@Valid @ModelAttribute ReverseRequest request) {
        return service.reverseGeocode(request.latitude(), request.longitude());
    }
}
