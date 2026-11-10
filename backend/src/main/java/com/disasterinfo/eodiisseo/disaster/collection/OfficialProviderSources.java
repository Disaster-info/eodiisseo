package com.disasterinfo.eodiisseo.disaster.collection;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import java.util.UUID;
/** Two names for DSSP-IF-00247 only. Never an institution-wide route or credential fallback. */
public final class OfficialProviderSources {
    private OfficialProviderSources(){}
    public static boolean active(OwnedDatabase db,UUID source,OfficialProviderClient.Provider provider){
        // Preserve the original strict contract for all existing callers and repository adapters.
        if(db.count("select count(*) from data_source where source_id=? and active and source_kind='DISASTER' and endpoint_key=?",source,provider.endpointKey)==1)return true;
        return provider==OfficialProviderClient.Provider.WILDFIRE_MESSAGES && db.count("select count(*) from data_source where source_id=? and active and source_kind='DISASTER' and endpoint_key='SAFETYDATA_DISASTER_MESSAGE'",source)==1;
    }
}