package com.disasterinfo.eodiisseo.ai.resource.vector;

import com.disasterinfo.eodiisseo.ai.chat.port.ChatOfficialResourceSearchPort;
import com.disasterinfo.eodiisseo.ai.resource.port.OfficialResourceStore;
import com.disasterinfo.eodiisseo.ai.resource.port.ResourceApprovalPort;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "eodiisseo.ai.vector.enabled", havingValue = "true")
class RagVectorConfiguration {
    static final String CURRENT_PLAN = " revoked_at is null and approved_at <= ? and valid_until > ? ";

    @Bean
    ApprovedRagPipeline approvedRagPipeline(OfficialResourceStore resources, ResourceApprovalPort approvals,
            JdbcTemplate sql, PlatformTransactionManager transactions, Clock clock,
            @Value("${EODIISSEO_AI_OLLAMA_BASE_URL:http://127.0.0.1:11434}") URI ollama,
            @Value("${EODIISSEO_AI_VECTOR_MODEL_DIGEST}") String digest,
            @Value("${EODIISSEO_AI_VECTOR_MAX_DISTANCE}") double distance,
            @Value("${EODIISSEO_AI_VECTOR_QUERY_BUDGET:PT2S}") String queryBudget) {
        var model = new EmbeddingPort.Model(LocalOllamaEmbedding.NOMIC_MODEL, digest, 768, 2048,
                LocalOllamaEmbedding.NOMIC_INPUT_VERSION);
        RankedRagIndex.assertSchema(sql);
        var vectors = new JdbcRagVectorStore(sql, transactions, new PgVectorBackend(sql), clock);
        var embedding = LocalOllamaEmbedding.forApprovedPipeline(ollama, model, clock);
        RagChunkGroup.PlanReview plans = (id, fingerprint, hash, now) -> Boolean.TRUE.equals(sql.queryForObject(
                "select exists(select 1 from rag_vector_plan where group_id=? and revision_fingerprint=? "
                        + "and manifest_hash=? and " + CURRENT_PLAN + ")",
                Boolean.class, id, fingerprint, hash, Timestamp.from(now), Timestamp.from(now)));
        return new ApprovedRagPipeline(resources, approvals, plans, vectors, embedding, model, clock,
                distance, Duration.parse(queryBudget));
    }

    @Bean
    @Primary
    ChatOfficialResourceSearchPort approvedVectorSearch(ApprovedRagPipeline pipeline) {
        return pipeline.approvedVectorSearch();
    }

    @Bean
    @ConditionalOnProperty(name = "eodiisseo.ai.vector.indexer.enabled", havingValue = "true")
    RankedRagIndex rankedRagIndex(ApprovedRagPipeline pipeline, JdbcTemplate sql, Clock clock) {
        return new RankedRagIndex(pipeline, sql, clock);
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnProperty(name = "eodiisseo.ai.vector.indexer.enabled", havingValue = "true")
    ThreadPoolTaskScheduler ragIndexScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("rag-index-");
        return scheduler;
    }

    record RankedRagIndex(ApprovedRagPipeline pipeline, JdbcTemplate sql, Clock clock) {
        private static final JsonMapper JSON = JsonMapper.builder().build();
        private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(RankedRagIndex.class);

        static void assertSchema(JdbcTemplate sql) {
            if (!Boolean.TRUE.equals(sql.queryForObject(
                    "select to_regclass('rag_vector_plan') is not null and to_regclass('rag_vector_embedding') is not null",
                    Boolean.class))) throw new IllegalStateException("APPROVED_RAG_MIGRATION_REQUIRED");
        }

        @Scheduled(fixedDelayString = "${EODIISSEO_AI_VECTOR_INDEX_DELAY_MS:30000}", scheduler = "ragIndexScheduler")
        public void indexApprovedPlans() {
            Timestamp now = Timestamp.from(clock.instant());
            var plans = sql.queryForList("""
                    select p.group_id, p.manifest_json, p.manifest_hash from rag_vector_plan p
                    left join rag_vector_work w on w.group_id=p.group_id and w.model_key=?
                    where
                    """ + CURRENT_PLAN + """
                    and (w.group_id is null or (w.attempts < 3 and (w.state='FAILED' or w.lease_until<=?)))
                    order by w.attempts nulls first, p.group_id limit 3
                    """, pipeline.modelKey(), now, now, now);
            for (var plan : plans) {
                try {
                    var group = JSON.readValue((String) plan.get("manifest_json"), RagChunkGroup.class);
                    if (!group.id().equals(plan.get("group_id")) || !group.manifestHash().equals(plan.get("manifest_hash"))) {
                        LOG.warn("RAG plan manifest mismatch group={}", plan.get("group_id"));
                        continue;
                    }
                    LOG.info("RAG indexing group={} status={}", group.id(), pipeline.index(group, Duration.ofSeconds(10)));
                } catch (RuntimeException failure) {
                    LOG.warn("RAG plan indexing failed group={} failureType={}", plan.get("group_id"),
                            failure.getClass().getSimpleName());
                }
            }
        }
    }
}
