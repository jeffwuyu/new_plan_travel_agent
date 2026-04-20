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

    /** Updates the status and optional error message for a document. */
    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("errorMessage") String errorMessage);

    /** Returns all documents with the given status. */
    List<RagDocument> findByStatus(@Param("status") String status);

    /** Hard-deletes a document record by primary key. */
    int deleteById(@Param("id") Long id);
}
