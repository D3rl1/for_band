package com.forband.section;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forband.auth.RequireRole;
import com.forband.auth.Role;
import com.forband.common.ApiResponse;
import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.log.OpLog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/sections")
@RequireRole(Role.ADMIN)
public class AdminSectionController {

    private final SectionMapper sectionMapper;

    public AdminSectionController(SectionMapper sectionMapper) {
        this.sectionMapper = sectionMapper;
    }

    public record SectionRequest(@NotBlank @Size(max = 32) String name,
                                 Integer orderNo) {
    }

    @PostMapping
    @OpLog(action = "SECTION_CREATE", targetType = "section")
    public ApiResponse<Section> create(@RequestBody @Valid SectionRequest request) {
        Long exists = sectionMapper.selectCount(
                new LambdaQueryWrapper<Section>().eq(Section::getName, request.name()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ErrorCode.ACCOUNT_DUPLICATE, "该声部名已存在");
        }
        Section section = new Section();
        section.setName(request.name());
        section.setOrderNo(request.orderNo() == null ? 0 : request.orderNo());
        sectionMapper.insert(section);
        return ApiResponse.ok(section);
    }

    @PutMapping("/{id}")
    @OpLog(action = "SECTION_UPDATE", targetType = "section")
    public ApiResponse<Section> update(@PathVariable long id,
                                       @RequestBody @Valid SectionRequest request) {
        Section section = sectionMapper.selectById(id);
        if (section == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "声部不存在");
        }
        section.setName(request.name());
        if (request.orderNo() != null) {
            section.setOrderNo(request.orderNo());
        }
        sectionMapper.updateById(section);
        return ApiResponse.ok(section);
    }
}
