package com.forband.issue;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface IssueCommentMapper extends BaseMapper<IssueComment> {

    /**
     * 列表页「回帖数」：只数 USER 行。系统状态行是时间线事件，不是团员的回帖，
     * 否则每次改状态都会让「回帖数」虚增。
     */
    @Select("""
            <script>
            SELECT issue_id AS issueId, COUNT(*) AS total
            FROM issue_comment
            WHERE kind = 'USER'
              AND issue_id IN
              <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY issue_id
            </script>
            """)
    List<IssueCount> countUserByIssueIds(@Param("ids") List<Long> ids);
}
