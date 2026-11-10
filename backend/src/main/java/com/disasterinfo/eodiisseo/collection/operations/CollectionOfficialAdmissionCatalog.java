package com.disasterinfo.eodiisseo.collection.operations;
import java.util.*;
/** Current code-level capability inventory, not an assertion that old plans were actually verified. */
public final class CollectionOfficialAdmissionCatalog {
    private static final Map<String,List<String>> BLOCKED=Map.of(
        "KMA_PORTAL_EQK_MSG",List.of("OFFICIAL_INCIDENT_REFERENCE_REQUIRED","SNAPSHOT_INCREMENTAL_CONTRACT_REQUIRED"),
        "KMA_HUB_EQK_MSG",List.of("HUB_ACCESS_CONTRACT_UNVERIFIED","OFFICIAL_INCIDENT_REFERENCE_REQUIRED","SNAPSHOT_INCREMENTAL_CONTRACT_REQUIRED"),
        "KMA_WARNING_LIST",List.of("WARNING_LIST_PROJECTION_NOT_IMPLEMENTED","SNAPSHOT_INCREMENTAL_CONTRACT_REQUIRED"),
        "KMA_RAIN_CODES",List.of("TOTAL_COUNT_CONTRADICTION_UNRESOLVED","COMPLETE_TEMPORAL_AREA_MAPPING_REQUIRED","SNAPSHOT_INCREMENTAL_CONTRACT_REQUIRED"),
        "MOIS_MESSAGES",List.of("MESSAGE_INCREMENTAL_CONTRACT_REQUIRED","MESSAGE_INCIDENT_LINK_UNVERIFIED"),
        "MOIS_SHELTERS",List.of("SHELTER_PAGING_AND_DEACTIVATION_CONTRACT_REQUIRED"),
        "MOIS_WILDFIRE",List.of("WILDFIRE_ID_SEQUENCE_STATUS_CONTRACT_REQUIRED","WILDFIRE_ADAPTER_DISABLED"));
    public List<String> productionBlockers(String contract){return BLOCKED.getOrDefault(contract,List.of("UNKNOWN_PROVIDER_CONTRACT"));}
    public boolean productionAdmissible(String contract){return productionBlockers(contract).isEmpty();}
    public Map<String,List<String>> status(){return BLOCKED;}
}