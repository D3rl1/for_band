package com.forband.health;

import com.forband.common.ApiResponse;
import com.forband.section.SectionMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final SectionMapper sectionMapper;

    public HealthController(SectionMapper sectionMapper) {
        this.sectionMapper = sectionMapper;
    }

    @GetMapping("/api/health")
    public ApiResponse<Map<String, Object>> health() {
        long sectionCount = sectionMapper.selectCount(null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("db", "UP");
        data.put("sectionCount", sectionCount);
        return ApiResponse.ok(data);
    }
}
