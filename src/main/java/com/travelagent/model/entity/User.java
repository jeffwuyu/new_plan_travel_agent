package com.travelagent.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * User entity. Maps to the `users` table.
 * Soft-deleted: records with deleted_at != null are treated as non-existent.
 */

/**
 * 中文注释：实体类，用于定义 User 的持久化数据结构。
 */

@Data
@NoArgsConstructor
public class User {

    private Long id;

    /** Unique display name */
    private String username;

    /** Unique email, used for login */
    private String email;

    /** BCrypt hashed password — never expose in responses */
    private String passwordHash;

    /**
     * User level: 1=REGULAR, 2=VIP, 3=ADMIN
     * @see com.travelagent.model.enums.UserLevel
     */
    private Integer userLevel;

    /** Account status: 1=active, 0=disabled */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** Null if not deleted; set on soft-delete (account cancellation). */
    private LocalDateTime deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isActive() {
        return Integer.valueOf(1).equals(status) && !isDeleted();
    }
}
