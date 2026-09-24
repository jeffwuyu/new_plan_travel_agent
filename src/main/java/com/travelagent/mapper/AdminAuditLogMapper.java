package com.travelagent.mapper;

import com.travelagent.model.entity.AdminAuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AdminAuditLogMapper {

    int insert(AdminAuditLog log);

    List<AdminAuditLog> findRecent(@Param("adminUserId") Long adminUserId,
                                   @Param("action") String action,
                                   @Param("limit") int limit);
}
