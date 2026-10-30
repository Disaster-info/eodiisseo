package com.disasterinfo.eodiisseo.weather.controller;
import com.disasterinfo.eodiisseo.weather.service.WeatherService;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
@RestController @RequestMapping("/api/v1/weather") @Profile("!test")
public class WeatherController {
    private final WeatherService service;
    public WeatherController(WeatherService service) { this.service=service; }
    @GetMapping("/current") public Map<String,Object> current(@AuthenticationPrincipal MemberPrincipal p,
        @RequestParam(required=false) Double latitude,@RequestParam(required=false) Double longitude,@RequestParam(required=false) UUID interestAreaId) {
        return service.current(p.memberId(),latitude,longitude,interestAreaId);
    }
}
