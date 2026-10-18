-- for_band 建表脚本。表结构以 docs/13-数据模型总表.md 为唯一权威，改动必须先改 docs/13。
-- 本机执行：mysql -uroot -p band < sql/schema.sql
-- ⚠️ **必须用管理员账号**（见 AGENTS.md「运行与环境」）：脚本含 `CREATE TABLE` 与守护式
--    `CREATE PROCEDURE`，应用账号 `band_app` 只有 DML 权限，拿它跑会直接报错。
--
-- ⚠️ 显式声明连接字符集：本脚本里**有中文数据字面量**（`band_tighten_leave_reason` 那条回填，
--    2026-09-28 加的）——不声明的话结果取决于客户端的默认值，写进库的可能是乱码。库本身是 utf8mb4。
SET NAMES utf8mb4;

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
    role             VARCHAR(20)  NOT NULL COMMENT 'ADMIN/LEADER/CONDUCTOR/SECTION_LEADER/MEMBER/HONORARY',
    status           VARCHAR(12)  NOT NULL COMMENT 'ACTIVE/PAUSED/LEFT/GRADUATED',
    account          VARCHAR(32)  NULL COMMENT '登录名',
    password_hash    VARCHAR(72)  NULL COMMENT 'BCrypt',
    initial_password VARCHAR(16)  NULL COMMENT '仅未登录期间有值，首次登录成功后置 NULL',
    last_login_at    DATETIME     NULL,
    is_system        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '管理员系统账号标记',
    joined_at        DATE         NULL COMMENT '入团日期',
    left_at          DATE         NULL COMMENT '离团日期，退团/毕业时写',
    honorary_until   DATE         NULL COMMENT '荣誉团员登录有效期；为空或已过期→不可登录，非荣誉团员恒为 NULL',
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
    id           BIGINT   NOT NULL AUTO_INCREMENT,
    token_hash   VARCHAR(64) NOT NULL COMMENT 'SHA-256 hex',
    member_id    BIGINT   NOT NULL,
    issued_at    DATETIME NOT NULL,
    expires_at   DATETIME NOT NULL COMMENT '签发 + 30 天',
    revoked_at   DATETIME NULL COMMENT '非空即失效',
    user_agent   VARCHAR(255) NULL COMMENT '登录时的 UA，存原文（展示时粗解析成"设备"，规则见 docs/01 §1.4）',
    ip           VARCHAR(45)  NULL COMMENT '登录 IP（IPv6 最长 45）。存完整值（审计用），只有展示时打码（SECURITY.md B9）',
    last_seen_at DATETIME     NULL COMMENT '最后活跃时间，用于"我在哪儿登录过"',
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

-- ⑥ attendance 考勤（docs/13 §13.3⑥）。四态 PRESENT/PARTIAL/LEAVE/ABSENT（DECISIONS D9 修订 / D33）：
-- 缺勤通常不落库、靠应到名单推导；ABSENT 行只来自人工改判已有记录。无 DDL 迁移。
CREATE TABLE IF NOT EXISTS attendance (
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    event_id  BIGINT      NOT NULL,
    member_id BIGINT      NOT NULL,
    status    VARCHAR(12) NOT NULL COMMENT 'PRESENT/PARTIAL/LEAVE/ABSENT',
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

-- ⑦ leave_request 请假申请（docs/13 §13.3⑦）。同人对同场只允许一条有效申请。
CREATE TABLE IF NOT EXISTS leave_request (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    event_id   BIGINT      NOT NULL,
    member_id  BIGINT      NOT NULL,
    reason     VARCHAR(255) NOT NULL COMMENT '理由',
    status     VARCHAR(12) NOT NULL COMMENT 'PENDING/APPROVED/REJECTED/CANCELED',
    applied_at DATETIME    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_event_member (event_id, member_id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '请假申请';

-- ⑧ leave_approval 请假待签记录（docs/13 §13.3⑧）。排练 2 条、声部课 1 条（DECISIONS D12）。
CREATE TABLE IF NOT EXISTS leave_approval (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    leave_request_id BIGINT      NOT NULL,
    required_role    VARCHAR(20) NOT NULL COMMENT 'SECTION_LEADER/LEADER，这一方需要谁来签',
    approver_id      BIGINT      NULL COMMENT '实际签署人，未签时 NULL',
    decision         VARCHAR(12) NOT NULL COMMENT 'PENDING/APPROVED/REJECTED',
    decided_at       DATETIME    NULL,
    note             VARCHAR(255) NULL COMMENT '审批意见',
    PRIMARY KEY (id),
    KEY idx_leave_request_id (leave_request_id),
    KEY idx_approver_id (approver_id),
    KEY idx_decision (decision)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '请假待签记录';

-- ㉑ feedback 反馈（docs/13 §13.3㉑，期 1.5）。连点两次就是两条，**不设幂等**。
CREATE TABLE IF NOT EXISTS feedback (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    member_id  BIGINT        NOT NULL COMMENT '提交人（FK→member）',
    content    VARCHAR(1000) NOT NULL COMMENT '反馈正文，必填',
    page_path  VARCHAR(255)  NULL COMMENT '提交时所在页，前端自动带（定位问题的第一线索）',
    user_agent VARCHAR(255)  NULL COMMENT '提交时的 UA 原文；展示时粗解析成"设备"（docs/01 §1.4）',
    status     VARCHAR(12)   NOT NULL COMMENT 'OPEN/HANDLED',
    handled_by BIGINT        NULL COMMENT '处理人（FK→member）。与 handled_at 一起就是留痕，故不进操作日志',
    handled_at DATETIME      NULL,
    admin_note VARCHAR(255)  NULL COMMENT '处理备注',
    created_at DATETIME      NOT NULL,
    PRIMARY KEY (id),
    KEY idx_status (status),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '反馈';

-- ㉒ release_note 更新公告（docs/13 §13.3㉒，期 1.5）。正文纯文本 + 换行，不渲染 HTML/markdown；
-- 发布后不可改不可删（要改就再发一条），所以没有 updated_at。
CREATE TABLE IF NOT EXISTS release_note (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    title        VARCHAR(64) NOT NULL,
    content      TEXT        NOT NULL COMMENT '正文，纯文本 + 换行',
    published_at DATETIME    NOT NULL COMMENT '发布时间（业务时间，显式赋值）',
    published_by BIGINT      NOT NULL COMMENT '发布人（FK→member）',
    created_at   DATETIME    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_published_at (published_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '更新公告';

-- ⑨ file 统一文件表（docs/13 §13.3⑨）。曲目附件（`06`）、演出照片（`07`）、练习录音（`08`）
-- **共用同一套上传/下载机制**（docs/08 §8.5），所以表只有一张、下载只走一个鉴权接口。
CREATE TABLE IF NOT EXISTS file (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    stored_name   VARCHAR(64)  NOT NULL COMMENT '磁盘文件名，**服务端随机生成**：原始名可猜、还可能带路径穿越字符',
    original_name VARCHAR(255) NULL COMMENT '原始文件名，仅下载时显示',
    content_type  VARCHAR(64)  NOT NULL COMMENT '服务端按**文件头**判定，不信客户端传来的 MIME',
    size_bytes    BIGINT       NOT NULL,
    sha256        VARCHAR(64)  NOT NULL COMMENT '内容哈希（校验损坏用；本期不做去重，见移交事项）',
    owner_id      BIGINT       NOT NULL COMMENT '上传者（FK→member）',
    created_at    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stored_name (stored_name),
    KEY idx_owner_id (owner_id),
    KEY idx_sha256 (sha256)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '统一文件表';

-- ⑩ piece 曲目（docs/13 §13.3⑩）。**不做"曲目 → 版本"父子结构**：不同版本就是两条记录（DECISIONS D14）。
CREATE TABLE IF NOT EXISTS piece (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    title        VARCHAR(64) NOT NULL,
    composer     VARCHAR(64) NULL COMMENT '作曲',
    arranger     VARCHAR(64) NULL COMMENT '编曲 / 移植',
    category     VARCHAR(32) NULL COMMENT '体裁',
    difficulty   TINYINT     NULL COMMENT '1~5',
    duration_sec INT         NULL,
    status       VARCHAR(16) NOT NULL COMMENT 'CANDIDATE 备选 / REHEARSING 在练 / READY 已成熟 / PAUSED 已停排',
    note         TEXT        NULL,
    created_by   BIGINT      NOT NULL COMMENT 'FK→member',
    updated_by   BIGINT      NULL COMMENT 'FK→member',
    created_at   DATETIME    NOT NULL,
    updated_at   DATETIME    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_status (status),
    KEY idx_title (title)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '曲目';

-- ⑪ piece_attachment 曲目附件（docs/13 §13.3⑪）。文件本体在 `file` 表，这里只做关联。
CREATE TABLE IF NOT EXISTS piece_attachment (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    piece_id    BIGINT      NOT NULL COMMENT 'FK→piece',
    kind        VARCHAR(12) NOT NULL COMMENT 'AUDIO 音频 / SCORE 谱子',
    file_id     BIGINT      NOT NULL COMMENT 'FK→file',
    uploaded_by BIGINT      NULL COMMENT 'FK→member',
    uploaded_at DATETIME    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_piece_id (piece_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '曲目附件';

-- ⑳ event_piece 活动关联曲目（docs/13 §13.3⑳，**期 3 起才建**）：让"这次练了哪些曲"可点开、可统计。
CREATE TABLE IF NOT EXISTS event_piece (
    id       BIGINT       NOT NULL AUTO_INCREMENT,
    event_id BIGINT       NOT NULL COMMENT 'FK→event',
    piece_id BIGINT       NOT NULL COMMENT 'FK→piece',
    order_no INT          NULL COMMENT '练习顺序',
    note     VARCHAR(255) NULL COMMENT '如"只练前两段"',
    PRIMARY KEY (id),
    KEY idx_event_id (event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '活动关联曲目';

-- ⑫ concert 演出（docs/13 §13.3⑫）。**演出不做签到**——现场忙着上台不可能点签到，
-- 改用「参演人员名单」（⑬）记录谁上过台（docs/07 §7.4）。**视频只存链接**，不托管视频文件。
CREATE TABLE IF NOT EXISTS concert (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    name           VARCHAR(64)  NOT NULL,
    date           DATE         NOT NULL,
    start_time     TIME         NULL,
    venue          VARCHAR(64)  NULL,
    host           VARCHAR(64)  NULL COMMENT '主办方 / 合作单位',
    poster_file_id BIGINT       NULL COMMENT 'FK→file',
    video_url      VARCHAR(255) NULL COMMENT '录像链接（B 站 / 网盘），**不托管视频文件**',
    summary        TEXT         NULL COMMENT '演出总结 / 复盘',
    note           TEXT         NULL,
    status         VARCHAR(12)  NOT NULL COMMENT 'PLANNED 计划 / DONE 已完成 / CANCELED 已取消',
    is_showcase    TINYINT      NOT NULL DEFAULT 0 COMMENT '1 = 这是一场「专场」，在「我们的专场」时间线上占一个节点（docs/17）',
    created_by     BIGINT       NOT NULL COMMENT 'FK→member',
    updated_by     BIGINT       NULL COMMENT 'FK→member',
    created_at     DATETIME     NOT NULL,
    updated_at     DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_date (date),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '演出';

-- ⑬ concert_cast 参演名单（docs/13 §13.3⑬）：**联合主键**。
-- ⚠️ **荣誉团员可以被列入**（"毕业了回团参演"很常见）：不计考勤、不能自己报名、不能签到。
CREATE TABLE IF NOT EXISTS concert_cast (
    concert_id BIGINT      NOT NULL COMMENT 'FK→concert',
    member_id  BIGINT      NOT NULL COMMENT 'FK→member（可以是已毕业/休团的人）',
    note       VARCHAR(64) NULL COMMENT '如"替补"',
    PRIMARY KEY (concert_id, member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '演出参演名单';

-- ⑭ concert_piece 演出曲目单（docs/13 §13.3⑭）：**曲目从曲目库选，不自由填写**。
-- **指挥放在曲目行上**——协奏曲请外聘指挥、某首由学生指挥，都能表达。
CREATE TABLE IF NOT EXISTS concert_piece (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    concert_id BIGINT       NOT NULL COMMENT 'FK→concert',
    piece_id   BIGINT       NOT NULL COMMENT 'FK→piece',
    order_no   INT          NOT NULL COMMENT '演出顺序',
    conductor  VARCHAR(32)  NULL COMMENT '这首的指挥（可能与别的曲目不同）',
    note       VARCHAR(255) NULL,
    PRIMARY KEY (id),
    KEY idx_concert_id (concert_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '演出曲目单';

-- ⑮ concert_photo 演出照片（docs/13 §13.3⑮）：文件本体在 `file` 表；**前端先压缩再传**。
CREATE TABLE IF NOT EXISTS concert_photo (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    concert_id  BIGINT       NOT NULL COMMENT 'FK→concert',
    file_id     BIGINT       NOT NULL COMMENT 'FK→file',
    caption     VARCHAR(128) NULL,
    uploaded_by BIGINT       NULL COMMENT 'FK→member',
    uploaded_at DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_concert_id (concert_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '演出照片';

-- ⑯ recording 练习录音（docs/13 §13.3⑯）。文件本体在 `file` 表（docs/08 §8.5 的统一机制）。
-- ⚠️ `section_id` 实现为**可空**（docs/13 原文写 NOT NULL）：因为**指挥 / 团长也能上传自己的录音**，
--    而他们没有声部（`member.section_id` 为 NULL）——写 NOT NULL 会把这些人直接堵死。
--    它只服务于"声部长看本声部"这一条，而指挥/团长本来就能看全部。已记移交请规格确认。
CREATE TABLE IF NOT EXISTS recording (
    id           BIGINT   NOT NULL AUTO_INCREMENT,
    member_id    BIGINT   NOT NULL COMMENT '上传者（FK→member）',
    section_id   BIGINT   NULL COMMENT '**冗余**：可见性按声部判定、查询高频；上传者无声部时为空',
    piece_id     BIGINT   NULL COMMENT '录的是哪首曲子（FK→piece，可空）',
    title        VARCHAR(64)  NULL,
    file_id      BIGINT   NOT NULL COMMENT 'FK→file',
    duration_sec INT      NULL COMMENT '**客户端上报**的时长（服务端不解析音频，拦不住时长）',
    size_bytes   BIGINT   NULL,
    uploaded_at  DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_member_id (member_id),
    KEY idx_section_id (section_id),
    KEY idx_piece_id (piece_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '练习录音';

-- ⑰ recording_comment 录音点评（docs/13 §13.3⑰）：**平铺按时间，不做父子嵌套**（docs/08 §8.4）。
-- 双向：声部长留言后团员可以回复追问——所以"能看见这条录音就能在这里说话"。
CREATE TABLE IF NOT EXISTS recording_comment (
    id           BIGINT   NOT NULL AUTO_INCREMENT,
    recording_id BIGINT   NOT NULL COMMENT 'FK→recording',
    author_id    BIGINT   NOT NULL COMMENT 'FK→member',
    content      TEXT     NOT NULL,
    created_at   DATETIME NOT NULL,
    updated_at   DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_recording_id (recording_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '录音点评';

-- ㉓ profile_visit 主页访客记录（docs/13 §13.3㉓，期 1.6 建）。
-- ⚠️ 去重**靠唯一键**（uk_viewer_host_date），**不靠代码判重**——代码判重挡不住并发；
--    所以 `visit_date` 必须是**显式的日期列**（`DATE(visited_at)` 这种函数表达式进不了唯一键）。
-- ⚠️ 不记 IP / UA；**不进操作日志**（docs/11 §11.3 的判据：对象自己已经记了"谁在何时来的"）。
CREATE TABLE IF NOT EXISTS profile_visit (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    viewer_id   BIGINT   NOT NULL COMMENT 'FK→member：谁来看的',
    host_id     BIGINT   NOT NULL COMMENT 'FK→member：看了谁的主页',
    visit_date  DATE     NOT NULL COMMENT '去重键的一部分：同一人同一天对同一人只一条',
    visited_at  DATETIME NOT NULL COMMENT '当天第一次来的时间点',
    created_at  DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_viewer_host_date (viewer_id, host_id, visit_date),
    KEY idx_host_date (host_id, visit_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '主页访客记录';

-- ㉔ showcase_photo 专场策展图（docs/13 §13.3㉔，期 1.7 建）。业务规则见 docs/17。
-- ⚠️ 与 ⑮ concert_photo（演出页的晒图墙，全员可传、不排序）是**两件事**：这张表是**专页的门面**，
--    **仅管理员**策展、**有顺序**、**有独家长文案**。允许复用同一张图（`file_id` 指向同一个 file，不重复占磁盘）。
-- ⚠️⚠️ 本表的 `file_id` 必须被文件下载规则认领（`ConcertPhotoFileAccessRule`，与 `concert_photo.file_id`、
--    `concert.poster_file_id` 一起），否则**专页的图只有管理员本人看得到**（交接单 #70 的同一个坑）。
CREATE TABLE IF NOT EXISTS showcase_photo (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    concert_id BIGINT   NOT NULL COMMENT 'FK→concert：哪一场专场',
    file_id    BIGINT   NOT NULL COMMENT 'FK→file：图片本体（统一文件表，docs/08 §8.5）',
    sort_no    INT      NOT NULL COMMENT '展示顺序（专页轮播按它排）。本项目唯一有排序语义的图',
    note       TEXT     NULL COMMENT '策展文案（外部导入的长文字），聚焦单图时显示全文',
    created_by BIGINT   NOT NULL COMMENT 'FK→member：谁加进来的',
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_concert_id (concert_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '专场策展图';

-- ㉕ section_note 上任声部长的留言（docs/13 §13.3㉕，2026-09-30 建）。业务规则见 docs/03 §3.5。
-- ⚠️ **不做「一声部一条」**（`section_id` **不加唯一键**）：它是**传承** —— 历届部长各留一条、
--    按 `created_at` 叠着看才是重点（`docs/13` §13.3㉕ 明写）。
-- ⚠️ `author_id` 的约束是"**写的那一刻**"的身份（王五卸任后仍是这条的作者）⇒ **不做成 DB 约束**，
--    是**服务层校验**（可变的历史状态做不成跨表约束，见 `docs/13` §13.3㉕）。
-- ⚠️ **任何人都不能删**（留痕性质，与 `operation_log` 同族的"只增"取向）⇒ 没有删除路径。
-- ⚠️ 写与改都进操作日志，action 串 `SECTION_NOTE_WRITE`（docs/11 §11.3）。
CREATE TABLE IF NOT EXISTS section_note (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    section_id BIGINT   NOT NULL COMMENT 'FK→section：哪条声部的留言',
    author_id  BIGINT   NOT NULL COMMENT 'FK→member：写的人（写的那一刻必须是该声部的声部长）',
    body       TEXT     NOT NULL COMMENT '留言正文（纯文本，保留换行——同 release_note 的口径）',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NULL COMMENT '本人可改；本项目按惯例在建行时也填（= created_at）',
    PRIMARY KEY (id),
    KEY idx_section_id (section_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '上任声部长的留言';

-- 存量库补列（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema 守护，保证本脚本可重复执行）。
DELIMITER $$
DROP PROCEDURE IF EXISTS band_add_honorary_until$$
CREATE PROCEDURE band_add_honorary_until()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'member'
          AND COLUMN_NAME = 'honorary_until'
    ) THEN
        ALTER TABLE member
            ADD COLUMN honorary_until DATE NULL COMMENT '荣誉团员登录有效期；为空或已过期→不可登录，非荣誉团员恒为 NULL' AFTER left_at;
    END IF;
END$$
CALL band_add_honorary_until()$$
DROP PROCEDURE band_add_honorary_until$$

-- 同上：存量库补会话管理三列（docs/13 §13.3④，2026-09-22/23）
DROP PROCEDURE IF EXISTS band_add_token_session_columns$$
CREATE PROCEDURE band_add_token_session_columns()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'user_token'
          AND COLUMN_NAME = 'user_agent'
    ) THEN
        ALTER TABLE user_token
            ADD COLUMN user_agent VARCHAR(255) NULL COMMENT '登录时的 UA，存原文（展示时粗解析成"设备"，规则见 docs/01 §1.4）' AFTER revoked_at;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'user_token'
          AND COLUMN_NAME = 'ip'
    ) THEN
        ALTER TABLE user_token
            ADD COLUMN ip VARCHAR(45) NULL COMMENT '登录 IP（IPv6 最长 45）。存完整值（审计用），只有展示时打码（SECURITY.md B9）' AFTER user_agent;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'user_token'
          AND COLUMN_NAME = 'last_seen_at'
    ) THEN
        ALTER TABLE user_token
            ADD COLUMN last_seen_at DATETIME NULL COMMENT '最后活跃时间，用于"我在哪儿登录过"' AFTER ip;
    END IF;
END$$
CALL band_add_token_session_columns()$$
DROP PROCEDURE band_add_token_session_columns$$

-- 同上：存量库补个人页四列（docs/13 §13.3②，期 1.5）。
-- ⚠️ avatar 存**受控标识符**（如 fluent-frog）不是 URL——预置清单是唯一权威、服务端校验 ∈ 清单 ∪ {null}，
-- 这是"零安全面"的关键，别改成存 URL。
DROP PROCEDURE IF EXISTS band_add_member_profile_columns$$
CREATE PROCEDURE band_add_member_profile_columns()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'member'
          AND COLUMN_NAME = 'avatar'
    ) THEN
        ALTER TABLE member
            ADD COLUMN avatar VARCHAR(32) NULL COMMENT '预置头像的受控标识符（如 fluent-frog），不是 URL；为空→界面回退姓名首字' AFTER note;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'member'
          AND COLUMN_NAME = 'intro'
    ) THEN
        ALTER TABLE member
            ADD COLUMN intro VARCHAR(100) NULL COMMENT '个人简介/签名，本人可写' AFTER avatar;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'member'
          AND COLUMN_NAME = 'contact'
    ) THEN
        ALTER TABLE member
            ADD COLUMN contact VARCHAR(128) NULL COMMENT '联系方式（自由文本，本人自己写）' AFTER intro;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'member'
          AND COLUMN_NAME = 'birthday'
    ) THEN
        ALTER TABLE member
            ADD COLUMN birthday DATE NULL COMMENT '生日。默认只本人 + 管理员可见；对外只到「月-日」（docs/13 §13.3②）' AFTER contact;
    END IF;
END$$
CALL band_add_member_profile_columns()$$
DROP PROCEDURE band_add_member_profile_columns$$

-- 同上：存量库补 concert.is_showcase（docs/13 §13.3⑫，期 1.7）。
-- ⚠️ 这是本脚本**第一个带默认值的 NOT NULL 迁移**（前三个都只加可空列）：`DEFAULT 0` 对已有行生效，
--    所以**上线前建的老演出全是 0**、不会被误当成专场（这正是这条迁移要的结果）。
-- ⚠️ 表本身（showcase_photo）不用过程——它是新表，`CREATE TABLE IF NOT EXISTS` 已幂等。
DROP PROCEDURE IF EXISTS band_add_concert_showcase_column$$
CREATE PROCEDURE band_add_concert_showcase_column()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'concert'
          AND COLUMN_NAME = 'is_showcase'
    ) THEN
        ALTER TABLE concert
            ADD COLUMN is_showcase TINYINT NOT NULL DEFAULT 0 COMMENT '1 = 这是一场「专场」，在「我们的专场」时间线上占一个节点（docs/17）' AFTER status;
    END IF;
END$$
CALL band_add_concert_showcase_column()$$
DROP PROCEDURE band_add_concert_showcase_column$$

-- 同上：存量库把 leave_request.reason **收紧成 NOT NULL**（docs/13 §13.3⑪ + §13.1，
-- 2026-09-25 定的"请假理由必填"）。
-- ⚠️⚠️ **这是本脚本里唯一一条"光重跑补不上"的迁移**：`reason` 的 NOT NULL 写在上面 CREATE TABLE 的
--    列定义里，而 `CREATE TABLE IF NOT EXISTS` **对已存在的表一个字都不改** ⇒ 先装过本系统、
--    后来才定"理由必填"的库（例如 2026-09-23 上线的服务器库），那一列会一直允许为空
--    （只有接口层的 `@NotBlank` 拦着，数据库自己不做最后一道保险）。所以补这段守护过程把它收掉。
-- ⚠️ **收紧之前必须先回填**：存量库里既然允许为空，就可能真有 NULL 行（2026-09-25 之前建的请假单）；
--    直接 `MODIFY … NOT NULL` 在严格模式下会报错、**把后面整段脚本一起打断**。
--    空值统一填成下面那句话；**已经填过理由的行一个字都不改**（这是"补记"不是"改写"）。
-- ⚠️ 幂等：列已经是 NOT NULL 时整段跳过；它是本脚本里**第一个中文数据字面量** ⇒ 见文件头的 `SET NAMES`。
DROP PROCEDURE IF EXISTS band_tighten_leave_reason$$
CREATE PROCEDURE band_tighten_leave_reason()
BEGIN
    DECLARE nullable INT DEFAULT 0;
    SELECT COUNT(*) INTO nullable FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'leave_request'
        AND COLUMN_NAME = 'reason'
        AND IS_NULLABLE = 'YES';
    IF nullable > 0 THEN
        UPDATE leave_request SET reason = '（历史数据，理由缺失）' WHERE reason IS NULL;
        ALTER TABLE leave_request
            MODIFY reason VARCHAR(255) NOT NULL COMMENT '理由';
    END IF;
END$$
CALL band_tighten_leave_reason()$$
DROP PROCEDURE band_tighten_leave_reason$$
DELIMITER ;
