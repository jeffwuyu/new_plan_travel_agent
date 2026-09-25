-- T05/T06: versioned source/chunk metadata and atomic publication marker.
ALTER TABLE rag_documents ADD COLUMN source_version INT NOT NULL DEFAULT 1;
ALTER TABLE rag_documents ADD COLUMN chunk_policy_version VARCHAR(64) NOT NULL DEFAULT 'paragraph-v1';
ALTER TABLE rag_documents ADD COLUMN index_version VARCHAR(128) NULL;
ALTER TABLE rag_documents ADD COLUMN content_hash VARCHAR(128) NULL;
ALTER TABLE rag_documents ADD COLUMN index_published TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE rag_chunks ADD COLUMN stable_chunk_id VARCHAR(160) NULL;
ALTER TABLE rag_chunks ADD COLUMN chunk_policy_version VARCHAR(64) NULL;
ALTER TABLE rag_chunks ADD COLUMN index_version VARCHAR(128) NULL;
ALTER TABLE rag_chunks ADD COLUMN source_start INT NULL;
ALTER TABLE rag_chunks ADD COLUMN source_end INT NULL;
ALTER TABLE rag_chunks ADD COLUMN locator_json TEXT NULL;
UPDATE rag_documents SET index_published = CASE WHEN status = 'indexed' THEN 1 ELSE 0 END;
CREATE UNIQUE INDEX uk_rag_stable_chunk_id ON rag_chunks(stable_chunk_id);
CREATE INDEX idx_rag_documents_index_publication ON rag_documents(status, index_published, index_version);
