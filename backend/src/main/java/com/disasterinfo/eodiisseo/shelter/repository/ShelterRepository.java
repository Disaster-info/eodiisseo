package com.disasterinfo.eodiisseo.shelter.repository;

import com.disasterinfo.eodiisseo.shelter.entity.Shelter;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 대피소 저장소 (Repository 계층).
 *
 * 실행 시에는 JdbcShelterRepository(PostgreSQL·PostGIS의 shelter 테이블)가 구현하고,
 * DB 없는 test 프로필에서는 InMemoryShelterRepository가 구현한다. Service는 이 인터페이스만 쓴다.
 * 조회 메서드는 조회 대상(active)인 대피소만 돌려준다.
 */
public interface ShelterRepository {

    /** 외부에서 조회한 대피소를 저장 (같은 원본이면 최신 값으로 덮어쓰고 다시 조회 대상으로 둔다) */
    void saveAll(Collection<Shelter> shelters);

    /** 원본에서 미사용으로 바뀐 대피소를 조회 대상에서 뺀다. 행은 지우지 않는다 (경로·이력 참조 보존) */
    void deactivateAll(Collection<UUID> ids);

    Optional<Shelter> findById(UUID id);

    /** 기준점에서 직선거리 radiusMeters 이내의 대피소. 순서는 보장하지 않는다 (정렬은 Service가 한다) */
    List<Shelter> findWithin(double latitude, double longitude, double radiusMeters);
}
