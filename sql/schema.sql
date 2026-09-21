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
