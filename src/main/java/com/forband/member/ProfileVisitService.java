package com.forband.member;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.forband.common.PageResult;
import com.forband.member.dto.ProfileVisitDtos.ProfileVisitView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 主页访客记录（`docs/13` §13.3㉓ + `docs/14` §14.6.4「最近来看过我的人」）。
 * <p>
 * 三条口径（规格写死）：
 * <ul>
 *   <li>**同一人同一天对同一人只记一条** —— 靠**唯一键**，<b>不靠代码判重</b>；</li>
 *   <li>**本人看自己不算**；</li>
 *   <li>**只有被访问者本人能看/能清自己的** —— 管理员 / 团长**也看不到别人的**。
 *       这里的方法**都按 `hostId` 过滤**、而 `hostId` 只可能来自 token（见控制器），
 *       所以"看不到别人的"是**结构性**保证的，不是靠一处 if。</li>
 * </ul>
 */
@Service
public class ProfileVisitService {

    private final ProfileVisitMapper profileVisitMapper;
    private final MemberMapper memberMapper;

    public ProfileVisitService(ProfileVisitMapper profileVisitMapper, MemberMapper memberMapper) {
        this.profileVisitMapper = profileVisitMapper;
        this.memberMapper = memberMapper;
    }

    /**
     * 记一次「某人看了某人的主页」。
     * <p>
     * ⚠️ **去重靠唯一键**：同一天第二次来会撞 `uk_viewer_host_date`，捕获后**静默返回**——
     * 这是**正常路径、不是错误**（`visited_at` 因此天然记的是"当天第一次"）。
     * <p>
     * ⚠️ **不要给这个方法加 `@Transactional`**：单条 insert，本来一次即提交；加了之后在**同一个事务里**
     * 把 `DuplicateKeyException` 吞掉会留下 rollback-only 的坑（后续操作全炸）。控制器方法本身不在事务里。
     */
    public void record(long viewerId, long hostId) {
        if (viewerId == hostId) {
            return;   // 本人看自己不算（规格硬要求）
        }
        ProfileVisit visit = new ProfileVisit();
        visit.setViewerId(viewerId);
        visit.setHostId(hostId);
        visit.setVisitDate(LocalDate.now());
        visit.setVisitedAt(LocalDateTime.now());
        try {
            profileVisitMapper.insert(visit);
        } catch (DuplicateKeyException e) {
            // 今天已经来过 —— 什么都不用做
        }
    }

    /** 谁看过我（倒序：最近来的在前）。 */
    public PageResult<ProfileVisitView> list(long hostId, int page, int size) {
        Page<ProfileVisit> result = profileVisitMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<ProfileVisit>()
                        .eq(ProfileVisit::getHostId, hostId)
                        .orderByDesc(ProfileVisit::getVisitDate)
                        .orderByDesc(ProfileVisit::getVisitedAt));
        List<Long> viewerIds = result.getRecords().stream()
                .map(ProfileVisit::getViewerId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> names = viewerIds.isEmpty() ? Map.of()
                : memberMapper.selectBatchIds(viewerIds).stream()
                        .collect(Collectors.toMap(Member::getId, Member::getName));
        List<ProfileVisitView> views = result.getRecords().stream()
                .map(row -> new ProfileVisitView(row.getViewerId(),
                        names.get(row.getViewerId()), row.getVisitDate()))
                .toList();
        return PageResult.of(views, result.getTotal(), page, size);
    }

    /**
     * 清空**自己的**访客记录。
     * <p>
     * ⚠️ 这是本项目**唯一允许"删"的记录类数据**（`docs/13` §13.3㉓ 写明理由）：操作日志那种只增不删
     * 是给**业务留痕 / 审计**用的；访客记录是**本人的私人数据**，按"控制权留在人手里"给本人一个删除口，
     * **且只能删自己的**（`hostId` 只来自 token）。
     */
    public void clear(long hostId) {
        profileVisitMapper.delete(new LambdaQueryWrapper<ProfileVisit>()
                .eq(ProfileVisit::getHostId, hostId));
    }
}
