package com.disasterinfo.eodiisseo.area.adapter;

import com.disasterinfo.eodiisseo.area.repository.AreaRepository;
import com.disasterinfo.eodiisseo.area.service.AreaService;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.dashboard.DashboardException;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.BaseLocation;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardLocationReader;
import com.disasterinfo.eodiisseo.location.service.LocationService;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component @Profile("!test")
public class AreaDashboardLocationReader implements DashboardLocationReader {
    private final AreaRepository repository;private final AreaService areas;
    public AreaDashboardLocationReader(AreaRepository repository,AreaService areas){this.repository=repository;this.areas=areas;}
    public BaseLocation resolveCoordinates(double latitude,double longitude){
        LocationService.coordinates(latitude,longitude);
        var matches = repository.coveringSigungu(latitude,longitude);
        if (matches.size() != 1) {
            String detail = matches.isEmpty() ? "Verified administrative boundary is unavailable."
                    : "The location is covered by multiple administrative boundaries.";
            throw new DashboardException(DashboardException.Reason.DEPENDENCY_UNAVAILABLE,detail);
        }
        return new BaseLocation((String)matches.getFirst().get("name"),latitude,longitude);
    }
    public BaseLocation resolveInterestArea(UUID member,UUID id){
        try{areas.owned(member,id);}catch(ApiProblem e){throw new DashboardException(e.status()==403?DashboardException.Reason.FORBIDDEN:DashboardException.Reason.NOT_FOUND,"Interest area is unavailable.");}
        var area=repository.center(member,id);
        if(area==null||area.get("latitude")==null||area.get("longitude")==null)throw new DashboardException(DashboardException.Reason.DEPENDENCY_UNAVAILABLE,"Verified administrative center is unavailable.");
        return new BaseLocation((String)area.get("name"),((Number)area.get("latitude")).doubleValue(),((Number)area.get("longitude")).doubleValue());
    }
}
