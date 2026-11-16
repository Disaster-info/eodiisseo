package com.disasterinfo.eodiisseo.shelter.repository;

import com.disasterinfo.eodiisseo.shelter.entity.Shelter;
import com.disasterinfo.eodiisseo.shelter.entity.ShelterType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대피소 저장소 — PostgreSQL·PostGIS의 shelter 테이블 (ERD 4.2.1, V2026102701 마이그레이션).
 *
 * - point는 geography(Point,4326). 저장은 ST_MakePoint(경도, 위도) 순서, 읽기는 ST_Y=위도·ST_X=경도
 * - 반경 검색은 ST_DWithin(GIST 인덱스 ix_shelter_point 사용). 거리 정렬·반경 확대는 ShelterService가 한다
 * - 같은 원본(source_id, source_record_key)은 덮어쓴다. shelter_id는 원본 키로 만든 UUID v5라 바뀌지 않는다
 */
@Repository
@Profile("!test")
public class JdbcShelterRepository implements ShelterRepository {

    // 지금 대피소 제공처는 SD-001 하나다. data_source는 V2026102703 마이그레이션이 등록한다.
    // 제공처가 늘면 Shelter가 자신의 제공처를 갖도록 바꾼다.
    static final String SOURCE_ENDPOINT_KEY = "SAFETYDATA_EARTHQUAKE_SHELTER";
    private static final String ORGANIZATION = "행정안전부";

    private static final String UPSERT = """
            insert into shelter(shelter_id, source_id, source_record_key, name, address, point,
                                source_updated_at, collected_at, active, shelter_type, capacity, is_underground)
            values (?, (select source_id from data_source where endpoint_key = ?), ?, ?, ?,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, now(), true, ?, ?, ?)
            on conflict (source_id, source_record_key) do update set
                name = excluded.name, address = excluded.address, point = excluded.point,
                source_updated_at = excluded.source_updated_at, collected_at = excluded.collected_at,
                active = true, shelter_type = excluded.shelter_type, capacity = excluded.capacity,
                is_underground = excluded.is_underground
            """;

    private static final String SELECT = """
            select shelter_id, source_record_key, name, address,
                   ST_Y(point::geometry) as latitude, ST_X(point::geometry) as longitude,
                   shelter_type, capacity, is_underground, source_updated_at
            from shelter
            """;

    private final JdbcTemplate jdbc;

    public JdbcShelterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void saveAll(Collection<Shelter> shelters) {
        // id 순서로 저장해 동시에 같은 지역을 저장하는 요청끼리 행 잠금 순서가 엇갈리지 않게 한다 (교착 방지)
        List<Shelter> ordered = shelters.stream().sorted(Comparator.comparing(Shelter::id)).toList();
        jdbc.batchUpdate(UPSERT, ordered, 500, (ps, s) -> {
            ps.setObject(1, s.id());
            ps.setString(2, SOURCE_ENDPOINT_KEY);
            ps.setString(3, s.sourceKey());
            ps.setString(4, s.name());
            ps.setString(5, s.address());
            ps.setDouble(6, s.longitude());
            ps.setDouble(7, s.latitude());
            ps.setTimestamp(8, s.updatedAt() == null ? null : Timestamp.from(s.updatedAt()));
            ps.setString(9, s.type().name());
            ps.setObject(10, s.capacity());
            ps.setObject(11, s.isUnderground());
        });
    }

    @Override
    @Transactional
    public void deactivateAll(Collection<UUID> ids) {
        List<UUID> ordered = ids.stream().sorted().toList();
        jdbc.batchUpdate("update shelter set active = false where shelter_id = ? and active", ordered, 500,
                (ps, id) -> ps.setObject(1, id));
    }

    @Override
    public Optional<Shelter> findById(UUID id) {
        return jdbc.query(SELECT + " where shelter_id = ? and active and point is not null", this::map, id)
                .stream().findFirst();
    }

    @Override
    public List<Shelter> findWithin(double latitude, double longitude, double radiusMeters) {
        // use_spheroid=false: 구면 거리로 비교해 ShelterService의 하버사인 직선거리와 기준을 맞춘다
        return jdbc.query(SELECT + """
                 where active and point is not null
                   and ST_DWithin(point, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, false)
                """, this::map, longitude, latitude, radiusMeters);
    }

    private Shelter map(ResultSet rs, int row) throws SQLException {
        Timestamp updatedAt = rs.getTimestamp("source_updated_at");
        return new Shelter(
                rs.getObject("shelter_id", UUID.class),
                rs.getString("source_record_key"),
                rs.getString("name"),
                rs.getString("address"),
                rs.getDouble("latitude"),
                rs.getDouble("longitude"),
                ShelterType.valueOf(rs.getString("shelter_type")),
                rs.getObject("capacity", Integer.class),
                rs.getObject("is_underground", Boolean.class),
                ORGANIZATION,
                updatedAt == null ? null : updatedAt.toInstant());
    }
}
