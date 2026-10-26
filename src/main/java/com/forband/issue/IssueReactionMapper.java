package com.forband.issue;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 「我也遇到」没有实体：一人一票由联合主键保证，重复 POST 用 INSERT IGNORE 走成功空操作。
 */
@Mapper
public interface IssueReactionMapper {

    @Insert("INSERT IGNORE INTO issue_reaction (issue_id, member_id, created_at) "
            + "VALUES (#{issueId}, #{memberId}, #{createdAt})")
    int insertIgnore(@Param("issueId") long issueId, @Param("memberId") long memberId,
                     @Param("createdAt") LocalDateTime createdAt);

    @Delete("DELETE FROM issue_reaction WHERE issue_id = #{issueId} AND member_id = #{memberId}")
    int delete(@Param("issueId") long issueId, @Param("memberId") long memberId);

    @Select("SELECT COUNT(*) FROM issue_reaction WHERE issue_id = #{issueId}")
    long countByIssue(@Param("issueId") long issueId);

    @Select("SELECT COUNT(*) FROM issue_reaction WHERE issue_id = #{issueId} AND member_id = #{memberId}")
    long countByIssueAndMember(@Param("issueId") long issueId, @Param("memberId") long memberId);

    @Select("""
            <script>
            SELECT issue_id AS issueId, COUNT(*) AS total
            FROM issue_reaction
            WHERE issue_id IN
              <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY issue_id
            </script>
            """)
    List<IssueCount> countsByIssueIds(@Param("ids") List<Long> ids);
}
