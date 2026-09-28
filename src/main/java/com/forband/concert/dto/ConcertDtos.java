package com.forband.concert.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public final class ConcertDtos {

    private ConcertDtos() {
    }

    /** 新建 / 修改演出（docs/07 §7.3）。`videoUrl` 只是字符串——服务端**不做任何抓取**。 */
    public record ConcertRequest(@NotBlank(message = "演出名不能为空") @Size(max = 64) String name,
                                 @NotNull(message = "日期不能为空") LocalDate date,
                                 LocalTime startTime,
                                 @Size(max = 64) String venue,
                                 @Size(max = 64) String host,
                                 Long posterFileId,
                                 @Size(max = 255) String videoUrl,
                                 String summary,
                                 String note,
                                 String status) {
    }

    /** 曲目单的一行（前端按顺序提交，`order_no` 由服务端按数组下标写死）。 */
    public record ProgramItem(Long pieceId, @Size(max = 32) String conductor,
                              @Size(max = 255) String note) {
    }

    public record ProgramRequest(List<ProgramItem> items) {
    }

    /** 参演名单：`memberId` **可以是已毕业 / 休团的人**（"毕业了回团参演"很常见）。 */
    public record CastItem(Long memberId, @Size(max = 64) String note) {
    }

    public record CastRequest(List<CastItem> members) {
    }

    public record PhotoRequest(Long fileId, @Size(max = 128) String caption) {
    }

    /**
     * 列表行：只要档案信息（详细内容走详情页）。
     * `isShowcase` = 这场是不是「专场」（`docs/17`）—— 策展管理页要按它分「专场」与「其他演出」两栏。
     */
    public record ConcertView(long id, String name, LocalDate date, LocalTime startTime, String venue,
                              String host, String status, String statusLabel, Long posterFileId,
                              Integer pieceCount, Integer castCount, Integer photoCount,
                              Boolean isShowcase) {
    }

    /** 曲目单的一行（带曲目元数据，免得前端再查一次）。 */
    public record ProgramRow(long pieceId, String title, String composer, String arranger,
                             String category, String pieceStatus, String pieceStatusLabel,
                             int orderNo, String conductor, String note) {
    }

    public record CastRow(long memberId, String name, String sectionName, String role,
                          String roleLabel, String status, String note) {
    }

    public record PhotoRow(long id, long fileId, String caption, String uploadedByName,
                           java.time.LocalDateTime uploadedAt) {
    }

    /** 详情：曲目单 + 参演名单 + 照片 + 复盘（docs/07 §7.3）。 */
    public record ConcertDetailView(ConcertView concert, List<ProgramRow> program,
                                    List<CastRow> cast, List<PhotoRow> photos,
                                    String summary, String videoUrl, String note) {
    }
}
