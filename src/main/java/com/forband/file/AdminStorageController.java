package com.forband.file;

import com.forband.auth.RequireRole;
import com.forband.auth.Role;
import com.forband.common.ApiResponse;
import com.forband.file.dto.FileDtos.StorageUsage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 存储用量（docs/08 §8.5「全局用量显示在**管理员后台**」）。**仅管理员**——它是系统维护数据，
 * 不是业务信息（团长 / 声部长都不该看到全站用量）。
 * <p>
 * ⚠️ 只读。规格里"永久保留、不自动清理"那条是**产品口径**，别在这里加任何删除逻辑。
 */
@RestController
@RequestMapping("/api/admin/storage")
@RequireRole(Role.ADMIN)
public class AdminStorageController {

    private final StorageUsageService storageUsageService;

    public AdminStorageController(StorageUsageService storageUsageService) {
        this.storageUsageService = storageUsageService;
    }

    @GetMapping
    public ApiResponse<StorageUsage> usage() {
        return ApiResponse.ok(storageUsageService.usage());
    }
}
