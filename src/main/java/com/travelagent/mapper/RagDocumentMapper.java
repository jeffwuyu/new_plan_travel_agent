package com.travelagent.mapper;

import com.travelagent.model.entity.RagDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 中文注释：Mapper 接口，负责 rag_documents 表的数据访问与持久化映射。
 */
@Mapper
public interface RagDocumentMapper {

    /** Inserts a new document record. Sets {@code id} via useGeneratedKeys. */
    int insert(RagDocument doc);

    /** Finds a document by primary key. Returns null if not found. */
    RagDocument findById(@Param("id") Long id);

    /** Finds a document by its unique OSS object key. Returns null if not found. */
    RagDocument findByOssKey(@Param("ossKey") String ossKey);

    /** Finds indexed documents by source type, preserving source separation. */
    List<RagDocument> findBySourceType(@Param("sourceType") String sourceType);

    /** Updates the status and optional error message for a document. */
    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("errorMessage") String errorMessage);

    /** Updates status, progress, retry count, and structured error information. */
    int updateLifecycle(@Param("id") Long id,
                        @Param("status") String status,
                        @Param("ingestProgress") Integer ingestProgress,
                        @Param("retryCount") Integer retryCount,
                        @Param("lastErrorCode") String lastErrorCode,
                        @Param("errorMessage") String errorMessage);

    int publishIndex(@Param("id") Long id, @Param("indexVersion") String indexVersion,
                     @Param("contentHash") String contentHash);

    /** Increments retry count before a manual reingest attempt. */
    int incrementRetryCount(@Param("id") Long id);

    /** Marks a document disabled so it no longer participates in retrieval. */
    int disableDocument(@Param("id") Long id);

    /** Clears disabled marker and returns the document to pending state. */
    int reenableDocument(@Param("id") Long id);

    /** Returns all documents with the given status. */
    List<RagDocument> findByStatus(@Param("status") String status);

    /** Hard-deletes a document record by primary key. */
    int deleteById(@Param("id") Long id);

    /** Returns all documents ordered by created_at DESC. */
    List<RagDocument> findAll();
}
