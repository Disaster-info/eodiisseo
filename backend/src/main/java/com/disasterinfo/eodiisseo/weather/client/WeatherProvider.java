package com.disasterinfo.eodiisseo.weather.client;
import com.disasterinfo.eodiisseo.weather.dto.WeatherObservation;
import com.disasterinfo.eodiisseo.weather.util.KmaGrid;

import java.time.Instant;
public interface WeatherProvider { WeatherObservation current(KmaGrid grid,Instant now); }
