-- PostgreSQL RAG schema for Hybrid RAG retrieval.
-- Use this when the runtime database is PostgreSQL with pgvector installed.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS rag_documents (
    id            BIGSERIAL PRIMARY KEY,
    oss_key       VARCHAR(512) NOT NULL UNIQUE,
    title         VARCHAR(255),
    region        VARCHAR(128),
    doc_type      VARCHAR(32),
    source_type   VARCHAR(32) NOT NULL DEFAULT 'static_knowledge'
        CHECK (source_type IN ('static_knowledge', 'user_preference')),
    source_name   VARCHAR(128),
    source_url    VARCHAR(512),
    metadata_json JSONB,
    status        VARCHAR(16) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'indexing', 'indexed', 'failed', 'disabled')),
    ingest_progress INT NOT NULL DEFAULT 0,
    retry_count   INT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(64),
    error_message TEXT,
    disabled_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_rag_documents_status ON rag_documents(status);
CREATE INDEX IF NOT EXISTS idx_rag_documents_region ON rag_documents(region);
CREATE INDEX IF NOT EXISTS idx_rag_documents_source_type ON rag_documents(source_type);

CREATE TABLE IF NOT EXISTS rag_chunks (
    id               BIGSERIAL PRIMARY KEY,
    document_id      BIGINT NOT NULL REFERENCES rag_documents(id) ON DELETE CASCADE,
    chunk_index      INT NOT NULL,
    chunk_text       TEXT NOT NULL,
    dashvector_id    VARCHAR(128),
    embedding_vector vector(1536),
    embedding_json   JSONB,
    search_text      TEXT,
    metadata_json    JSONB,
    search_tsv       TSVECTOR GENERATED ALWAYS AS (
        to_tsvector('simple', coalesce(search_text, '') || ' ' || coalesce(chunk_text, ''))
    ) STORED,
    token_count      INT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_rag_chunks_document_id ON rag_chunks(document_id);
CREATE INDEX IF NOT EXISTS idx_rag_chunks_dashvector_id ON rag_chunks(dashvector_id);
CREATE INDEX IF NOT EXISTS idx_rag_chunks_search_tsv ON rag_chunks USING GIN(search_tsv);
CREATE INDEX IF NOT EXISTS idx_rag_chunks_embedding_hnsw
    ON rag_chunks USING hnsw (embedding_vector vector_cosine_ops);
