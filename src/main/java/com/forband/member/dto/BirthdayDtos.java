package com.forband.member.dto;

public final class BirthdayDtos {

    private BirthdayDtos() {
    }

    /**
     * 「今天的寿星」要显示的东西（`docs/03` §3.3、`docs/14` §14.6.4「生日通告」）。
     * <p>
     * ⚠️ **故意不含 `birthday`**：接口只给姓名与职称，所以"**生日对外只到「月-日」**"那条口径
     * （`docs/00` §0.6 的 T17、`docs/03` §3.4 的可见性表）**不用动**——
     * 一旦把生日下发出去，前端就等于拿到了全值，那条可见性规则等于被绕过。
     * <p>
     * {@code role} 回**原始角色枚举**（与 `MemberView` 一致），中文由前端 `BAND.roleLabel` 转。
     */
    public record BirthdayView(String name, String sectionName, String instrument, String role) {
    }
}
