-- Compatibility patch for older local MySQL schemas.
-- Safe to run before restarting the application if the users table predates soft-delete support.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS user_level TINYINT NOT NULL DEFAULT 1 COMMENT '1=REGULAR, 2=VIP, 3=ADMIN',
    ADD COLUMN IF NOT EXISTS status TINYINT NOT NULL DEFAULT 1 COMMENT '1=active, 0=disabled',
    ADD COLUMN IF NOT EXISTS created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS deleted_at DATETIME NULL DEFAULT NULL COMMENT 'NULL means not deleted (soft delete)';
