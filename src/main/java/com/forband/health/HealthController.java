package com.forband.health;

import com.forband.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运维存活端点（docs/12 §12.6「运维端点」）：**只回服务存活状态，不含任何业务数据**。
 * 该端点免登录（`refresh.sh` / `restart.sh` 轮询用），所以尤其不能带业务字段或查库计数。
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("db", "UP");
        return ApiResponse.ok(data);
    }
}
