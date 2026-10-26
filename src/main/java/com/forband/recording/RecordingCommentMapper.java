package com.forband.recording;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RecordingCommentMapper extends BaseMapper<RecordingComment> {

    /** 管理视图一次取完本周所有录音的点评数（否则每条录音一次 COUNT，N+1）。 */
    @Select("<script>"
            + "SELECT recording_id AS recording_id, COUNT(*) AS total"
            + " FROM recording_comment WHERE recording_id IN"
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " GROUP BY recording_id"
            + "</script>")
    List<RecordingCommentCount> countByRecordingIds(@Param("ids") List<Long> ids);

    class RecordingCommentCount {
        private Long recordingId;
        private Long total;

        public Long getRecordingId() {
            return recordingId;
        }

        public void setRecordingId(Long recordingId) {
            this.recordingId = recordingId;
        }

        public Long getTotal() {
            return total;
        }

        public void setTotal(Long total) {
            this.total = total;
        }
    }
}
