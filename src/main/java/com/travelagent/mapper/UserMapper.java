package com.travelagent.mapper;

import com.travelagent.model.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用途：`users` 表的 MyBatis Mapper 接口。
 *
 * <p>所有查询均隐式过滤 `deleted_at IS NULL`（软删除）。管理员接口
 * （如 `AdminController`）需要的分页查询，以及按用户状态筛选用户 ID
 * 列表的方法，也统一定义在这里，并通过 PageHelper 实现透明分页。</p>
 */
@Mapper
public interface UserMapper {

    /** 新增用户记录，并通过 `useGeneratedKeys` 自动回填主键 id。 */
    int insert(User user);

    /** 按主键查询用户，仅返回未软删除的数据。 */
    User findById(@Param("id") Long id);

    /** 按邮箱查询用户，主要用于登录流程。 */
    User findByEmail(@Param("email") String email);

    /** 按用户名查询用户，主要用于唯一性校验。 */
    User findByUsername(@Param("username") String username);

    /** 更新用户字段，目前用于更新用户名、等级、状态等信息。 */
    int update(User user);

    /** 软删除用户，将 `deleted_at` 设置为当前时间。 */
    int softDelete(@Param("id") Long id);

    /** 校验邮箱是否已被未删除用户占用。 */
    boolean existsByEmail(@Param("email") String email);

    /** 校验用户名是否已被未删除用户占用。 */
    boolean existsByUsername(@Param("username") String username);

    /**
     * 管理员分页查询接口，返回全部未软删除用户。
     *
     * <p>调用前执行 `PageHelper.startPage(page, size)` 即可自动分页，
     * 无需在此方法中显式传入 `offset` / `limit` 参数。</p>
     */
    List<User> findAll();

    /**
     * 返回全部活跃用户（`status = 1` 且未软删除）的 ID 列表。
     *
     * <p>该方法供 `QuotaServiceImpl.snapshotAllActiveUsers()` 等批量处理场景使用，
     * 仅查询 `id` 字段，避免加载不必要的用户数据。</p>
     */
    List<Long> findAllActiveIds();

    /**
     * 轻量级用户活跃状态检查，供 `JwtAuthInterceptor` 在鉴权时调用。
     *
     * @return `true` 表示账号正常，`false` 表示账号已禁用或已软删除，
     *     `null` 表示用户不存在
     */
    Boolean findUserActiveStatus(@Param("userId") Long userId);
}
