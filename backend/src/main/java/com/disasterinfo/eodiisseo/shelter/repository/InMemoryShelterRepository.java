package com.disasterinfo.eodiisseo.shelter.repository;

import com.disasterinfo.eodiisseo.common.util.GeoUtils;
import com.disasterinfo.eodiisseo.shelter.entity.Shelter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * DB 없는 test 프로필용 대피소 저장소 — 서버 메모리에 보관한다.
 * 실제 실행은 JdbcShelterRepository(shelter 테이블)를 쓴다. 단위 테스트에서도 DB 대신 쓴다.
 *
 * 최대 MAX_SIZE건만 보관하고, 넘치면 가장 오래 쓰이지 않은 것부터 지운다 (LRU)
 */
@Repository
@Profile("test")
public class InMemoryShelterRepository implements ShelterRepository {

    static final int MAX_SIZE = 20_000; // 전국 지진옥외대피장소 수(약 1만 수천 건)를 넉넉히 담는 크기

    // accessOrder=true: 조회할 때마다 맨 뒤로 옮겨져 가장 오래 안 쓰인 항목이 맨 앞에 남는다
    private final Map<UUID, Shelter> store = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Shelter> eldest) {
            return size() > MAX_SIZE;
        }
    };

    @Override
    public synchronized void saveAll(Collection<Shelter> shelters) {
        shelters.forEach(s -> store.put(s.id(), s));
    }

    @Override
    public synchronized void deactivateAll(Collection<UUID> ids) {
        ids.forEach(store::remove); // 메모리 구현은 조회 대상만 두므로 빼기만 한다
    }

    @Override
    public synchronized Optional<Shelter> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public synchronized List<Shelter> findWithin(double latitude, double longitude, double radiusMeters) {
        return store.values().stream()
                .filter(s -> GeoUtils.distanceKm(latitude, longitude, s.latitude(), s.longitude()) * 1000 <= radiusMeters)
                .toList();
    }
}
