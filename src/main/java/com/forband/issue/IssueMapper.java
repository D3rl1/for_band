package com.forband.issue;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface IssueMapper extends BaseMapper<Issue> {

    /** 导航红点只问"最新动态时刻"，一条问题都没有时返回 null。 */
    @Select("SELECT MAX(updated_at) FROM issue")
    LocalDateTime latestUpdatedAt();
}
