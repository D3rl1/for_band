package com.forband.auth;

/** 六级角色，定义见 docs/02-权限模型.md（§2.3 荣誉团员）。权限判定统一走 PermissionService（DECISIONS D16）。 */
public enum Role {
    ADMIN, LEADER, CONDUCTOR, SECTION_LEADER, MEMBER, HONORARY
}
