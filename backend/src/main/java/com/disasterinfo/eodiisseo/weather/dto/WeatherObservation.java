package com.disasterinfo.eodiisseo.weather.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
public record WeatherObservation(BigDecimal temperature,String code,String basis,Instant observedAt,
    Instant collectedAt,Instant skyValidAt,Map<String,String> sourcePayload) {}
