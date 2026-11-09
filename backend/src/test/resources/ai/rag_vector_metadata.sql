-- TEST FIXTURE ONLY. Applied explicitly to a generated 55433 disposable DB, never by Flyway.
create table rag_vector_work (
 group_id uuid not null, model_key char(64) not null, resource_id uuid not null references official_resource,
 manifest_hash char(64) not null, state varchar(10) not null check(state in ('WORKING','READY','FAILED')),
 claim_id uuid,lease_until timestamptz,attempts integer not null check(attempts between 1 and 3),error_code varchar(80),
 primary key(group_id,model_key)
);
create table rag_vector_group (
 group_id uuid not null,model_key char(64) not null,resource_id uuid not null references official_resource,
 revision_fingerprint char(64) not null,manifest_hash char(64) not null,hazard_type varchar(30) not null,
 manifest_json text not null,embedding_model varchar(150) not null,embedding_model_digest varchar(71) not null,
 embedding_dimension integer not null check(embedding_dimension between 1 and 4096),input_version varchar(100) not null,
 max_tokens integer not null check(max_tokens>0),created_at timestamptz not null,
 primary key(group_id,model_key),foreign key(group_id,model_key) references rag_vector_work
);
create table rag_vector_chunk (
 chunk_id uuid not null,group_id uuid not null,model_key char(64) not null,resource_id uuid not null references official_resource,
 revision_fingerprint char(64) not null,paragraph_index integer not null check(paragraph_index>=0),
 paragraph_text text not null,paragraph_hash char(64) not null,source_locator varchar(500) not null,
 section_path text not null,mandatory_context_group uuid not null,extraction_version varchar(100) not null,
 primary key(chunk_id,model_key),foreign key(group_id,model_key) references rag_vector_group on delete cascade,
 unique(resource_id,revision_fingerprint,paragraph_index,model_key,extraction_version)
);
-- This is deliberately NOT pgvector: fake transport/persistence for PostgreSQL transaction tests.
create table rag_test_vector (
 chunk_id uuid not null,model_key char(64) not null,embedding_dimension integer not null,
 embedding_values double precision[] not null,primary key(chunk_id,model_key),
 foreign key(chunk_id,model_key) references rag_vector_chunk on delete cascade,
 check(cardinality(embedding_values)=embedding_dimension)
);