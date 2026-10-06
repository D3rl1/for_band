package com.forband.section;

import com.forband.auth.RequireRole;
import com.forband.common.ApiResponse;
import com.forband.section.dto.SectionDtos.SectionView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/sections")
@RequireRole
public class SectionController {

    private final SectionService sectionService;

    public SectionController(SectionService sectionService) {
        this.sectionService = sectionService;
    }

    @GetMapping
    public ApiResponse<List<SectionView>> list() {
        return ApiResponse.ok(sectionService.list());
    }
}
