-- for_band 建表脚本。表结构以 docs/13-数据模型总表.md 为唯一权威，改动必须先改 docs/13。
-- 本机执行：mysql -uband_app -p band < sql/schema.sql

-- ① section 声部（docs/13 §13.3①）
CREATE TABLE IF NOT EXISTS section (
    id       BIGINT      NOT NULL AUTO_INCREMENT,
    name     VARCHAR(32) NOT NULL COMMENT '声部名',
    order_no INT         NOT NULL DEFAULT 0 COMMENT '显示顺序',
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '声部';

-- ② member 团员与账号（docs/13 §13.3②）。绝不物理删除本表行，退团/毕业用 status 标记。
CREATE TABLE IF NOT EXISTS member (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    name             VARCHAR(32)  NOT NULL COMMENT '姓名',
    student_no       VARCHAR(32)  NULL COMMENT '学号',
    section_id       BIGINT       NULL COMMENT '所属声部，管理员系统账号为 NULL',
    instrument       VARCHAR(32)  NULL COMMENT '乐器',
    phone            VARCHAR(20)  NULL COMMENT '手机号，仅管理员/团长/指挥可见',
    role             VARCHAR(20)  NOT NULL COMMENT 'ADMIN/LEADER/CONDUCTOR/SECTION_LEADER/MEMBER',
    status           VARCHAR(12)  NOT NULL COMMENT 'ACTIVE/PAUSED/LEFT/GRADUATED',
    account          VARCHAR(32)  NULL COMMENT '登录名',
    password_hash    VARCHAR(72)  NULL COMMENT 'BCrypt',
    initial_password VARCHAR(16)  NULL COMMENT '仅未登录期间有值，首次登录成功后置 NULL',
    last_login_at    DATETIME     NULL,
    is_system        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '管理员系统账号标记',
    joined_at        DATE         NULL COMMENT '入团日期',
    left_at          DATE         NULL COMMENT '离团日期，退团/毕业时写',
    alumni_message   TEXT         NULL COMMENT '毕业寄语（期 6 历届成员页用）',
    note             VARCHAR(255) NULL COMMENT '备注',
    created_at       DATETIME     NOT NULL,
    updated_at       DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account (account),
    KEY idx_section_id (section_id),
    KEY idx_status (status),
    KEY idx_role (role),
    KEY idx_is_system (is_system)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '团员与账号';

-- ③ member_role_history 角色变更历史（docs/13 §13.3③）。变更角色时必须同时写这条历史。
CREATE TABLE IF NOT EXISTS member_role_history (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    member_id  BIGINT      NOT NULL,
    role       VARCHAR(20) NOT NULL COMMENT '当时的角色',
    section_id BIGINT      NULL COMMENT '当时所属声部',
    from_date  DATE        NOT NULL COMMENT '生效日期',
    to_date    DATE        NULL COMMENT '结束日期，NULL = 仍在任',
    created_by BIGINT      NULL COMMENT '谁记录的',
    created_at DATETIME    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_member_id (member_id),
    KEY idx_to_date (to_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '角色变更历史';

-- ④ user_token 登录令牌（docs/13 §13.3④）。只存哈希，不存原文。
CREATE TABLE IF NOT EXISTS user_token (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    token_hash VARCHAR(64) NOT NULL COMMENT 'SHA-256 hex',
    member_id  BIGINT   NOT NULL,
    issued_at  DATETIME NOT NULL,
    expires_at DATETIME NOT NULL COMMENT '签发 + 30 天',
    revoked_at DATETIME NULL COMMENT '非空即失效',
    PRIMARY KEY (id),
    UNIQUE KEY uk_token_hash (token_hash),
    KEY idx_member_id (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '登录令牌';

-- ⑤ event 活动：排练 / 声部课 / 彩排（docs/13 §13.3⑤）。三类共用一张表，不拆（DECISIONS D8）。
CREATE TABLE IF NOT EXISTS event (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    kind       VARCHAR(20) NOT NULL COMMENT 'REHEARSAL/SECTION_CLASS/DRESS_REHEARSAL',
    title      VARCHAR(64) NOT NULL,
    date       DATE        NOT NULL,
    start_time TIME        NOT NULL COMMENT '签到窗口靠它算',
    end_time   TIME        NOT NULL COMMENT '签到窗口靠它算',
    location   VARCHAR(64) NULL,
    section_id BIGINT      NULL COMMENT '仅 SECTION_CLASS 有值',
    content    TEXT        NULL COMMENT '期 1 自由文本',
    batch_id   VARCHAR(36) NULL COMMENT '批量生成的批次标识',
    status     VARCHAR(12) NOT NULL COMMENT 'NORMAL/CANCELED',
    created_by BIGINT      NOT NULL,
    updated_by BIGINT      NULL COMMENT 'H2 留痕靠它',
    created_at DATETIME    NOT NULL,
    updated_at DATETIME    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_date (date),
    KEY idx_kind (kind),
    KEY idx_section_id (section_id),
    KEY idx_batch_id (batch_id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '活动';

-- ⑥ attendance 考勤（docs/13 §13.3⑥）。只存 PRESENT/LEAVE，缺勤靠推导（DECISIONS D9）。
CREATE TABLE IF NOT EXISTS attendance (
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    event_id  BIGINT      NOT NULL,
    member_id BIGINT      NOT NULL,
    status    VARCHAR(12) NOT NULL COMMENT 'PRESENT/LEAVE',
    marked_at DATETIME    NOT NULL COMMENT '签到时间点（不判断迟到，D17）',
    marked_by BIGINT      NOT NULL COMMENT '代签留痕，本人签时 = member_id',
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_member (event_id, member_id),
    KEY idx_member_id (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '考勤记录';

-- ⑱ operation_log 操作留痕（docs/13 §13.3⑱）。只增不改不删，不提供任何修改/删除接口。
CREATE TABLE IF NOT EXISTS operation_log (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    operator_id BIGINT      NOT NULL,
    action      VARCHAR(32) NOT NULL,
    target_type VARCHAR(32) NULL,
    target_id   BIGINT      NULL,
    `before`    JSON        NULL,
    `after`     JSON        NULL,
    ip          VARCHAR(45) NULL,
    created_at  DATETIME    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_operator_id (operator_id),
    KEY idx_target (target_type, target_id),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '操作留痕';
