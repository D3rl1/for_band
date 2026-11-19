package com.forband.member.dto;

import java.time.LocalDate;

/** 主页访客记录的出参（`docs/03` §3.3 / `docs/14` §14.6.4「最近来看过我的人」）。 */
public final class ProfileVisitDtos {

    private ProfileVisitDtos() {
    }

    /**
     * 「谁 · 哪天来过」。
     * <p>
     * ⚠️ **没有"来了几次"这个字段**，而且**不许加**（`docs/14` 写死）：去重粒度就是"同一人同一天只一条"，
     * 次数没有意义，而且那是**攀比式可视化**——本项目一贯不做。
     */
    public record ProfileVisitView(long viewerId, String viewerName, LocalDate visitDate) {
    }
}
