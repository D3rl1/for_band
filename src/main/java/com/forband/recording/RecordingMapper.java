package com.forband.recording;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RecordingMapper extends BaseMapper<Recording> {

    /**
     * 历史周汇总（docs/08 §8.4）：按「上传时间所在周的周一」分组。
     * <p>
     * ⚠️ 分组不能靠 Java 侧算——一周可能有几千条录音；`DATE_SUB(DATE(...), INTERVAL WEEKDAY(...) DAY)`
     * 与后端 {@code weekStartOf()} 是同一套定义（周一为一周之始），改一处必须同时改另一处。
     */
    @Select("<script>"
            + "SELECT DATE_SUB(DATE(uploaded_at), INTERVAL WEEKDAY(uploaded_at) DAY) AS week_start,"
            + " COUNT(*) AS total, COUNT(DISTINCT section_id) AS section_count"
            + " FROM recording WHERE uploaded_at &lt; #{upperExclusive}"
            + "<if test='sectionId != null'> AND section_id = #{sectionId}</if>"
            + " GROUP BY week_start ORDER BY week_start DESC LIMIT #{limit}"
            + "</script>")
    List<WeekStat> selectWeekStats(@Param("upperExclusive") LocalDateTime upperExclusive,
                                   @Param("sectionId") Long sectionId,
                                   @Param("limit") int limit);

    /**
     * 每个作业的提交人数：**按人去重**（一人交两条录音也只算 1 人）。
     * 只传本周的作业 id，列表通常只有几条——一次 IN 打捞，避免逐条沿作业查人数。
     */
    @Select("<script>"
            + "SELECT assignment_id AS assignment_id, COUNT(DISTINCT member_id) AS submitted_count"
            + " FROM recording WHERE assignment_id IN"
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " GROUP BY assignment_id"
            + "</script>")
    List<AssignmentSubmissionCount> countSubmissions(@Param("ids") List<Long> ids);

    /** `mapUnderscoreToCamelCase` 打开后，`week_start` 自动落到这里。 */
    class WeekStat {
        private LocalDate weekStart;
        private Long total;
        private Long sectionCount;

        public LocalDate getWeekStart() {
            return weekStart;
        }

        public void setWeekStart(LocalDate weekStart) {
            this.weekStart = weekStart;
        }

        public Long getTotal() {
            return total;
        }

        public void setTotal(Long total) {
            this.total = total;
        }

        public Long getSectionCount() {
            return sectionCount;
        }

        public void setSectionCount(Long sectionCount) {
            this.sectionCount = sectionCount;
        }
    }

    class AssignmentSubmissionCount {
        private Long assignmentId;
        private Long submittedCount;

        public Long getAssignmentId() {
            return assignmentId;
        }

        public void setAssignmentId(Long assignmentId) {
            this.assignmentId = assignmentId;
        }

        public Long getSubmittedCount() {
            return submittedCount;
        }

        public void setSubmittedCount(Long submittedCount) {
            this.submittedCount = submittedCount;
        }
    }
}
