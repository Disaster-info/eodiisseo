package com.disasterinfo.eodiisseo.collection.ownership;

import java.util.*;

/** Contract identity is independent of target identity and credential quota identity. No HTTP routing. */
public final class CollectionContractRegistry {
    public record Contract(String key, String api, Set<String> sourceAliases) {
        public Contract { sourceAliases = Set.copyOf(sourceAliases); }
    }
    private final Map<String, Contract> contracts;
    public CollectionContractRegistry() {
        var entries = List.of(
            new Contract("KMA_PORTAL_EQK_MSG", "EqkInfoService/getEqkMsg", Set.of("KMA_EARTHQUAKE")),
            new Contract("KMA_HUB_EQK_MSG", "EqkInfoService/getEqkMsg", Set.of("KMA_EARTHQUAKE")),
            new Contract("KMA_WARNING_LIST", "getWthrWrnList", Set.of("KMA_WARNING")),
            new Contract("KMA_RAIN_CODES", "getPwnCd", Set.of("KMA_HEAVY_RAIN")),
            new Contract("MOIS_MESSAGES", "DSSP-IF-00247", Set.of("SAFETYDATA_DISASTER_MESSAGE", "MOIS_DISASTER_MESSAGES")),
            new Contract("MOIS_SHELTERS", "DSSP-IF-10943", Set.of("SAFETYDATA_EARTHQUAKE_SHELTER")),
            new Contract("MOIS_WILDFIRE", "DSSP-IF-10346", Set.of("SAFETYDATA_WILDFIRE")));
        var map = new LinkedHashMap<String, Contract>();
        entries.forEach(c -> map.put(c.key(), c)); contracts = Map.copyOf(map);
    }
    public Contract require(String key, String alias) {
        var contract = contracts.get(key);
        if (contract == null || !contract.sourceAliases().contains(alias))
            throw new IllegalArgumentException("Explicit supported contract/source assignment required");
        return contract;
    }
    public Collection<Contract> contracts() { return contracts.values(); }
}