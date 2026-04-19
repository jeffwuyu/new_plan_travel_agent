package com.travelagent.mapper;

import com.travelagent.model.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用途：users 表的 MyBatis Mapper 接口。
 *
 * 所有查询均隐式过滤 deleted_at IS NULL（软删除）。
 * 管理员接口（AdminController）需要的分页查询和用户 ID 列表方法也在此定义，
 * 通过 PageHelper 插件实现透明分页。
 */
@Mapper
public interface UserMapper {

    /** 新增用户行，自动填充 id（useGeneratedKeys）。 */
    int insert(User user);

    /** 按主键查询（仅未软删除）。 */
    User findById(@Param("id") Long id);

    /** 按邮箱查询（用于登录）。 */
    User findByEmail(@Param("email") String email);

    /** 按用户名查询（用于唯一性校验）。 */
    User findByUsername(@Param("username") String username);

    /** 更新用户字段（level、status）。 */
    int update(User user);

    /** 软删除：设置 deleted_at=NOW()。 */
    int softDelete(@Param("id") Long id);

    /** 校验邮箱是否已被未删除用户使用。 */
    boolean existsByEmail(@Param("email") String email);

    /** 校验用户名是否已被占用。 */
    boolean existsByUsername(@Param("username") String username);

    /**
     * 管理员分页查询：返回全部未软删除用户。
     *
     * 使用 PageHelper 插件：调用前执行 PageHelper.startPage(page, size) 即可自动分页，
     * 无需在此方法中传入 offset/limit 参数。
     */
    List<User> findAll();

    /**
     * 返回所有活跃（status=1 且未软删除）用户的 ID 列表。
     *
     * 供 QuotaServiceImpl.snapshotAllActiveUsers() 批量快照配额时使用。
     * 仅返回 ID，避免加载不必要的字段。
     */
    List<Long> findAllActiveIds();
}
