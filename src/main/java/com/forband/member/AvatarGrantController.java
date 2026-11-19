package com.forband.member;

import com.forband.auth.CurrentUser;
import com.forband.auth.RequireRole;
import com.forband.auth.Role;
import com.forband.common.ApiResponse;
import com.forband.log.OpAction;
import com.forband.log.OpLog;
import com.forband.member.dto.AvatarGrantDtos.GrantRequest;
import com.forband.member.dto.AvatarGrantDtos.GrantResult;
import com.forband.member.dto.AvatarGrantDtos.GrantRow;
import com.forband.member.dto.AvatarGrantDtos.RevokeResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 纪念头像发放（`docs/02` §2.4、#266）。**仅管理员**——团长不因能看团员资料而获得发放权。
 * <p>
 * 三个动作都进操作日志：发放 / 撤回各自一个 action 串，撤销只标记不删行，
 * `operation_log` 里能回答"谁在什么时候给谁发了哪张、又为什么收回"。
 */
@RestController
@RequestMapping("/api/admin/avatar-grants")
@RequireRole(Role.ADMIN)
public class AvatarGrantController {

    private final AvatarGrantService avatarGrantService;

    public AvatarGrantController(AvatarGrantService avatarGrantService) {
        this.avatarGrantService = avatarGrantService;
    }

    @PostMapping
    @OpLog(action = OpAction.AVATAR_GRANT, targetType = "avatar_grant")
    public ApiResponse<GrantResult> grant(@RequestBody @Valid GrantRequest request,
                                          CurrentUser operator) {
        return ApiResponse.ok(avatarGrantService.grant(request.avatarId(), request.memberIds(),
                request.batch(), request.note(), operator));
    }

    @DeleteMapping("/{memberId}/{avatarId}")
    @OpLog(action = OpAction.AVATAR_GRANT_REVOKE, targetType = "avatar_grant")
    public ApiResponse<RevokeResult> revoke(@PathVariable long memberId,
                                            @PathVariable String avatarId,
                                            @RequestParam String reason,
                                            CurrentUser operator) {
        return ApiResponse.ok(avatarGrantService.revoke(memberId, avatarId, reason, operator));
    }

    @GetMapping
    public ApiResponse<List<GrantRow>> list(@RequestParam(required = false) String avatarId,
                                            @RequestParam(required = false) String batch) {
        return ApiResponse.ok(avatarGrantService.list(avatarId, batch));
    }
}
