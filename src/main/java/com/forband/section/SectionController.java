package com.forband.section;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.forband.auth.RequireRole;
import com.forband.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/sections")
@RequireRole
public class SectionController {

    private final SectionMapper sectionMapper;

    public SectionController(SectionMapper sectionMapper) {
        this.sectionMapper = sectionMapper;
    }

    @GetMapping
    public ApiResponse<List<Section>> list() {
        List<Section> sections = sectionMapper.selectList(
                new QueryWrapper<Section>().orderByAsc("order_no"));
        return ApiResponse.ok(sections);
    }
}
