package com.disasterinfo.eodiisseo.shelter.repository;

import com.disasterinfo.eodiisseo.shelter.entity.Shelter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * DB 연동 전 임시 대피소 저장소 — 주변 조회(API-SHELTER-001)에서 받은 대피소를 서버 메모리에 보관해
 * 상세 조회(API-SHELTER-002)와 경로 조회(API-ROUTE-001)가 shelterId로 찾을 수 있게 한다.
 *
 * 한계 (DB 연동 시 해소):
 * - 서버를 재시작하면 비워진다 → 주변 조회를 한 번 해야 상세·경로 조회가 가능 (그 전에는 404)
 * - 최대 MAX_SIZE건만 보관하고, 넘치면 가장 오래 쓰이지 않은 것부터 지운다 (LRU)
 */
@Repository
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
    public synchronized Optional<Shelter> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }
}
