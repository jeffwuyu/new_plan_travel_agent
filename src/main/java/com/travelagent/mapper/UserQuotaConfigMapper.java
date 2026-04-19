package com.travelagent.mapper;

import com.travelagent.model.entity.UserQuotaConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 中文注释：Mapper 接口，负责 User Quota Config Mapper 相关的数据访问与持久化映射。
 */

@Mapper
public interface UserQuotaConfigMapper {

    UserQuotaConfig findByUserLevel(@Param("userLevel") int userLevel);

    int update(UserQuotaConfig config);
}
