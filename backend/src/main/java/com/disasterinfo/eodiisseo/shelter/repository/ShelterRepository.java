package com.disasterinfo.eodiisseo.shelter.repository;

import com.disasterinfo.eodiisseo.shelter.entity.Shelter;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * 대피소 저장소 (Repository 계층).
 *
 * 지금은 InMemoryShelterRepository(서버 메모리)가 구현한다. DB가 준비되면 Spring Data JPA 구현으로 바꾸고,
 * Service 코드는 이 인터페이스만 쓰므로 그대로 둔다.
 */
public interface ShelterRepository {

    /** 외부에서 조회한 대피소를 저장 (같은 id면 최신 값으로 덮어씀) */
    void saveAll(Collection<Shelter> shelters);

    Optional<Shelter> findById(UUID id);
}
