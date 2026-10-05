package com.forband.member;

import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 团员查找 + 写侧闸门。原先 {@code requireMember} / {@code requireWritableMember} 在 member、attendance
 * 等 service 各写一遍，这里收成一处。
 */
@Component
public class MemberLookup {

    private final MemberMapper memberMapper;

    public MemberLookup(MemberMapper memberMapper) {
        this.memberMapper = memberMapper;
    }

    public Member requireMember(long id) {
        Member member = memberMapper.selectById(id);
        if (member == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "团员不存在");
        }
        return member;
    }

    /**
     * 写侧闸门（`docs/01` §1.3）：`is_system = 1` 的账号**任何写操作一律 404**——**不承认它存在**
     * （回 403 等于承认它存在，多一处可探测的信息）。
     */
    public Member requireWritableMember(long id) {
        Member member = requireMember(id);
        if (Boolean.TRUE.equals(member.getIsSystem())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return member;
    }
}
