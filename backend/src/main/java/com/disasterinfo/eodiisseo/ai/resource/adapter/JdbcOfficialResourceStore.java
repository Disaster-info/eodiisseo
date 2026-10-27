package com.disasterinfo.eodiisseo.ai.resource.adapter;

import com.disasterinfo.eodiisseo.ai.resource.entity.OfficialResourceDocument;
import com.disasterinfo.eodiisseo.ai.resource.port.OfficialResourceStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Prepared JDBC against the existing ERD; no global JPA schema discovery or migration is required. */
@Repository
public class JdbcOfficialResourceStore implements OfficialResourceStore {
    public static final int MAX_CANDIDATES = 60;
    private static final String COLUMNS = "resource_id,agency_name,title,source_url,version_label,content_text,checked_at";
    private final Optional<JdbcTemplate> jdbc;

    public JdbcOfficialResourceStore(Optional<DataSource> dataSource) {
        this.jdbc = Objects.requireNonNull(dataSource).map(JdbcTemplate::new);
    }
    @Override public boolean isAvailable() { return jdbc.isPresent(); }

    @Override @Transactional(readOnly = true)
    public Optional<OfficialResourceDocument> findById(UUID resourceId) {
        return required().query("select " + COLUMNS + " from official_resource where resource_id = ?",
                JdbcOfficialResourceStore::map, Objects.requireNonNull(resourceId)).stream().findFirst();
    }

    @Override @Transactional
    public OfficialResourceDocument append(OfficialResourceDocument document) {
        Objects.requireNonNull(document);
        required().update("insert into official_resource(" + COLUMNS + ") values (?,?,?,?,?,?,?) on conflict (resource_id) do nothing",
                document.getResourceId(), document.getAgencyName(), document.getTitle(), document.getSourceUrl(),
                document.getVersionLabel(), document.getContentText(), Timestamp.from(document.getCheckedAt()));
        var persisted = findById(document.getResourceId()).orElseThrow(() -> new IllegalStateException("Official resource insertion failed."));
        if (!persisted.revisionFingerprint().equals(document.revisionFingerprint())) {
            throw new IllegalArgumentException("Publish changed official resources under a new resource ID.");
        }
        return persisted;
    }

    @Override @Transactional(readOnly = true)
    public List<OfficialResourceDocument> searchCandidates(List<String> terms, int limit) {
        if (terms == null || terms.isEmpty() || terms.size() > 3 || limit < 1 || limit > MAX_CANDIDATES
                || terms.stream().anyMatch(term -> term == null || term.isBlank() || term.length() > 30)) {
            throw new IllegalArgumentException("Invalid bounded official resource query.");
        }
        // position() treats %, _ and quotes literally; neither wildcards nor SQL are interpolated.
        String predicates = String.join(" or ", java.util.Collections.nCopies(terms.size(),
                "position(? in lower(title || ' ' || content_text)) > 0"));
        List<Object> parameters = new ArrayList<>(terms.stream().map(term -> term.toLowerCase(Locale.ROOT)).toList());
        parameters.add(limit);
        return required().query("select " + COLUMNS + " from official_resource where content_text is not null and ("
                + predicates + ") order by checked_at desc, resource_id asc limit ?",
                JdbcOfficialResourceStore::map, parameters.toArray());
    }

    private JdbcTemplate required() {
        return jdbc.orElseThrow(() -> new IllegalStateException("Official resource persistence is unavailable."));
    }
    private static OfficialResourceDocument map(ResultSet row, int index) throws SQLException {
        return new OfficialResourceDocument(row.getObject("resource_id", UUID.class), row.getString("agency_name"),
                row.getString("title"), row.getString("source_url"), row.getString("version_label"),
                row.getString("content_text"), row.getTimestamp("checked_at").toInstant());
    }
}
