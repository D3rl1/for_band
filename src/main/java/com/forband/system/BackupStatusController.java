package com.forband.system;

import com.forband.auth.RequireRole;
import com.forband.auth.Role;
import com.forband.common.ApiResponse;
import com.forband.system.BackupStatusService.Status;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 备份状态（docs/12 §12.5「⚡ 备份成败怎么让应用知道」②）。
 * <p>
 * **仅管理员**——与 `GET /api/admin/storage` 同档：它是系统维护数据，不是业务信息。
 * <p>
 * ⚠️ 只读。应用**不发起备份、不写状态文件**（那里由服务器上的备份脚本负责）。
 */
@RestController
@RequestMapping("/api/admin/backup-status")
@RequireRole(Role.ADMIN)
public class BackupStatusController {

    private final BackupStatusService backupStatusService;

    public BackupStatusController(BackupStatusService backupStatusService) {
        this.backupStatusService = backupStatusService;
    }

    @GetMapping
    public ApiResponse<Status> status() {
        return ApiResponse.ok(backupStatusService.read());
    }
}
