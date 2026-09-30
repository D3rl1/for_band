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
     * <p>
     * ⚠️ **`id` 是 2026-09-30 为 `#115` 加的**：欢迎行那条文案的判据是"**今天我在这个名单里**"
     * （`docs/14` §14.6.4「欢迎行」），而前端手里只有 `me.id` —— 没有 `id` 就没法回答"我在不在"。
     * 它**不泄露任何新东西**（名单本来就是"今天谁生日"的公开通告，姓名与职称都在里面）。
     */
    public record BirthdayView(Long id, String name, String sectionName, String instrument, String role) {
    }
}
