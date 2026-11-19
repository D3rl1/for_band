package com.forband.concert;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.forband.auth.CurrentUser;
import com.forband.auth.PermissionService;
import com.forband.auth.Role;
import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.concert.dto.ShowcaseDtos.AddPhotoRequest;
import com.forband.concert.dto.ShowcaseDtos.CandidateRow;
import com.forband.concert.dto.ShowcaseDtos.NoteRequest;
import com.forband.concert.dto.ShowcaseDtos.OpenRequest;
import com.forband.concert.dto.ShowcaseDtos.OpenResult;
import com.forband.concert.dto.ShowcaseDtos.OrderRequest;
import com.forband.concert.dto.ShowcaseDtos.ShowcasePhotoRow;
import com.forband.concert.dto.ShowcaseDtos.TimelineNode;
import com.forband.concert.dto.ShowcaseDtos.YearPhoto;
import com.forband.file.FileService;
import com.forband.file.StoredFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「我们的专场」专页（`docs/17`）：时间线**按年聚合**、策展**仅管理员**。
 * <p>
 * ⚠️ **两个容易做错的地方**：
 * ① **没有策展图的年份不出现在时间线上**（`docs/17` §17.4）——管理页要提示，但展示侧**不许出现空节点**；
 * ② **一个年份节点的图 = 该年所有专场的策展图串起来**（不是一场一张），排序口径 = 「演出日期 → `sort_no`」。
 */
@Service
public class ShowcaseService {

    private final ConcertMapper concertMapper;
    private final ConcertPhotoMapper photoMapper;
    private final ShowcasePhotoMapper showcasePhotoMapper;
    private final ConcertCastMapper castMapper;
    private final FileService fileService;
    private final PermissionService permissionService;

    public ShowcaseService(ConcertMapper concertMapper, ConcertPhotoMapper photoMapper,
                           ShowcasePhotoMapper showcasePhotoMapper, ConcertCastMapper castMapper,
                           FileService fileService, PermissionService permissionService) {
        this.concertMapper = concertMapper;
        this.photoMapper = photoMapper;
        this.showcasePhotoMapper = showcasePhotoMapper;
        this.castMapper = castMapper;
        this.fileService = fileService;
        this.permissionService = permissionService;
    }

    /**
     * 某成员**参演过多少场专场**（`docs/14` §14.6.4「个人页」第 6 条，`交接单 #117`）。
     * <p>
     * ⚠️ **只数专场**（`concert.is_showcase = 1`）—— 校庆晚会那类普通演出不算（用户口径原话：
     * 「其他的**参演演出专场数**可以放在**个人主页**，**别人查看的时候能看到**」）。
     * ⚠️ **0 就是 0**（不是 `—`）：与"出勤率 0/0 无意义"不是一回事。
     */
    public long memberShowcaseCount(long memberId) {
        List<Long> concertIds = castMapper.selectList(new LambdaQueryWrapper<ConcertCast>()
                        .eq(ConcertCast::getMemberId, memberId)
                        .select(ConcertCast::getConcertId))
                .stream().map(ConcertCast::getConcertId).distinct().toList();
        if (concertIds.isEmpty()) {
            return 0;
        }
        return concertMapper.selectCount(new LambdaQueryWrapper<Concert>()
                .eq(Concert::getIsShowcase, true)
                .in(Concert::getId, concertIds));
    }

    // ---------- 展示侧（登录即可，含荣誉团员） ----------

    /** 时间线：`is_showcase = 1` 的专场按年份聚合；年份升序；**没有图的年份不出现**。 */
    public List<TimelineNode> timeline() {
        List<Concert> concerts = showcaseConcerts(null);
        Map<Long, Concert> byId = new LinkedHashMap<>();
        concerts.forEach((c) -> byId.put(c.getId(), c));

        // 年份升序（concerts 已按日期升序 ⇒ 首次遇到的年份就是最小的）
        Map<Integer, List<ShowcasePhoto>> byYear = new LinkedHashMap<>();
        for (ShowcasePhoto p : orderedPhotos(byId)) {
            byYear.computeIfAbsent(year(byId.get(p.getConcertId())), (k) -> new ArrayList<>()).add(p);
        }
        List<TimelineNode> nodes = new ArrayList<>();
        byYear.forEach((y, list) -> nodes.add(new TimelineNode(y, list.size(), list.get(0).getFileId())));
        return nodes;
    }

    /** 某一年全部策展图，**按「演出日期 → `sort_no`」排**（`docs/17` §17.3）。 */
    public List<YearPhoto> yearPhotos(int year) {
        List<Concert> concerts = showcaseConcerts(year);
        Map<Long, Concert> byId = new LinkedHashMap<>();
        concerts.forEach((c) -> byId.put(c.getId(), c));
        return orderedPhotos(byId).stream()
                .map((p) -> new YearPhoto(p.getId(), p.getConcertId(), p.getFileId(),
                        p.getNote(), p.getSortNo()))
                .toList();
    }

    // ---------- 策展侧（仅管理员） ----------

    /** 开一个新时间点：**建出来就是一条普通演出记录**，之后照常由 管理员 / 团长 / 指挥 用 `07` 的接口维护。 */
    @Transactional
    public OpenResult open(OpenRequest request, CurrentUser operator) {
        checkAdmin(operator);
        // ⚠️ 幂等失败要说得出来（docs/17 §17.3）：同名同日的专场已经在了 → 10001，别静默成功
        Long exists = concertMapper.selectCount(new LambdaQueryWrapper<Concert>()
                .eq(Concert::getIsShowcase, true)
                .eq(Concert::getName, request.name())
                .eq(Concert::getDate, request.date()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "这一场已经是专场了");
        }
        Concert concert = new Concert();
        concert.setName(request.name());
        concert.setDate(request.date());
        concert.setVenue(request.venue());
        concert.setStatus(ConcertStatus.PLANNED);
        concert.setIsShowcase(true);
        concert.setCreatedBy(operator.memberId());
        concertMapper.insert(concert);
        return new OpenResult(concert.getId(), concert.getName(), concert.getDate(),
                concert.getVenue(), true);
    }

    /** 设为 / 取消专场：**只翻标记**，绝不删演出档案（`docs/17` §17.3）。 */
    @Transactional
    public void toggle(long concertId, boolean isShowcase, CurrentUser operator) {
        checkAdmin(operator);
        Concert concert = requireConcert(concertId);
        concert.setIsShowcase(isShowcase);
        concert.setUpdatedBy(operator.memberId());
        concertMapper.updateById(concert);
    }

    /** 这场演出**晒图墙上已有的**照片，供"从已有的里挑"。⚠️ 只读，不改 `concert_photo` 一个字。 */
    public List<CandidateRow> candidates(long concertId) {
        requireConcert(concertId);
        return photoMapper.selectList(new LambdaQueryWrapper<ConcertPhoto>()
                        .eq(ConcertPhoto::getConcertId, concertId)
                        .orderByDesc(ConcertPhoto::getUploadedAt)
                        .orderByDesc(ConcertPhoto::getId)).stream()
                .map((p) -> new CandidateRow(p.getId(), p.getFileId(), p.getCaption()))
                .toList();
    }

    /** 加一张策展图：`sort_no` 追加到末尾。 */
    @Transactional
    public ShowcasePhotoRow addPhoto(long concertId, AddPhotoRequest request, CurrentUser operator) {
        checkAdmin(operator);
        requireConcert(concertId);
        StoredFile file = fileService.require(request.fileId());
        ShowcasePhoto row = new ShowcasePhoto();
        row.setConcertId(concertId);
        row.setFileId(file.getId());
        row.setSortNo(nextSortNo(concertId));
        row.setNote(request.note());
        row.setCreatedBy(operator.memberId());
        row.setCreatedAt(LocalDateTime.now());
        showcasePhotoMapper.insert(row);
        return rowOf(row);
    }

    /**
     * 整批设顺序（`photoIds` 按数组顺序即最终顺序）。
     * ⚠️ 数组里的 id **必须都属于这场专场**，否则 `10001`（`docs/17` §17.3）。
     * 没列进来的（前端理应全列）按原顺序排到后面，免得留下重复的 `sort_no`。
     */
    @Transactional
    public List<ShowcasePhotoRow> setOrder(long concertId, OrderRequest request, CurrentUser operator) {
        checkAdmin(operator);
        requireConcert(concertId);
        List<Long> ids = request.photoIds() == null ? List.of() : request.photoIds();
        Map<Long, ShowcasePhoto> byId = new LinkedHashMap<>();
        photosOf(concertId).forEach((p) -> byId.put(p.getId(), p));
        for (Long id : ids) {
            if (id == null || !byId.containsKey(id)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "排序里有不属于这场专场的图片");
            }
        }
        int n = 1;
        for (Long id : ids) {
            writeSortNo(byId.get(id), n++);
        }
        for (ShowcasePhoto p : byId.values()) {
            if (!ids.contains(p.getId())) {
                writeSortNo(p, n++);
            }
        }
        return photosOf(concertId).stream().map(ShowcaseService::rowOf).toList();
    }

    /** 改这一张的文案。 */
    @Transactional
    public void setNote(long concertId, long photoId, NoteRequest request, CurrentUser operator) {
        checkAdmin(operator);
        requireConcert(concertId);
        ShowcasePhoto photo = requirePhoto(concertId, photoId);
        // ⚠️ 不能用 updateById：它忽略 null 字段 ⇒ "把文案清空"会静默失效
        showcasePhotoMapper.update(null, new LambdaUpdateWrapper<ShowcasePhoto>()
                .eq(ShowcasePhoto::getId, photo.getId())
                .set(ShowcasePhoto::getNote, request.note()));
    }

    /** 把这张移出专页：**只删关联行，不删 `file`**（同一张图可能还在晒图墙上）。 */
    @Transactional
    public void deletePhoto(long concertId, long photoId, CurrentUser operator) {
        checkAdmin(operator);
        requireConcert(concertId);
        requirePhoto(concertId, photoId);
        showcasePhotoMapper.deleteById(photoId);
    }

    // ---------- 内部 ----------

    /** 专场列表：`year` 给了就只取那一年的（按日期升序，年份自然升序）。 */
    private List<Concert> showcaseConcerts(Integer year) {
        LambdaQueryWrapper<Concert> wrapper = new LambdaQueryWrapper<Concert>()
                .eq(Concert::getIsShowcase, true)
                .orderByAsc(Concert::getDate).orderByAsc(Concert::getId);
        if (year != null) {
            wrapper.between(Concert::getDate, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
        }
        return concertMapper.selectList(wrapper);
    }

    /** 这些专场下的全部策展图，**按「演出日期 → `sort_no` → id」排**（封面 = 每组第一张）。 */
    private List<ShowcasePhoto> orderedPhotos(Map<Long, Concert> byId) {
        if (byId.isEmpty()) {
            return List.of();
        }
        List<ShowcasePhoto> photos = new ArrayList<>(showcasePhotoMapper.selectList(
                new LambdaQueryWrapper<ShowcasePhoto>().in(ShowcasePhoto::getConcertId, byId.keySet())));
        photos.sort(Comparator
                .comparing((ShowcasePhoto p) -> byId.get(p.getConcertId()).getDate())
                .thenComparing(ShowcasePhoto::getSortNo)
                .thenComparing(ShowcasePhoto::getId));
        return photos;
    }

    private List<ShowcasePhoto> photosOf(long concertId) {
        return showcasePhotoMapper.selectList(new LambdaQueryWrapper<ShowcasePhoto>()
                .eq(ShowcasePhoto::getConcertId, concertId)
                .orderByAsc(ShowcasePhoto::getSortNo).orderByAsc(ShowcasePhoto::getId));
    }

    private int nextSortNo(long concertId) {
        return photosOf(concertId).stream().mapToInt(ShowcasePhoto::getSortNo).max().orElse(0) + 1;
    }

    private void writeSortNo(ShowcasePhoto photo, int sortNo) {
        showcasePhotoMapper.update(null, new LambdaUpdateWrapper<ShowcasePhoto>()
                .eq(ShowcasePhoto::getId, photo.getId())
                .set(ShowcasePhoto::getSortNo, sortNo));
    }

    private static int year(Concert concert) {
        return concert.getDate().getYear();
    }

    private static ShowcasePhotoRow rowOf(ShowcasePhoto photo) {
        return new ShowcasePhotoRow(photo.getId(), photo.getFileId(), photo.getSortNo(), photo.getNote());
    }

    private ShowcasePhoto requirePhoto(long concertId, long photoId) {
        ShowcasePhoto photo = showcasePhotoMapper.selectById(photoId);
        if (photo == null || photo.getConcertId() != concertId) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "策展图不存在");
        }
        return photo;
    }

    private Concert requireConcert(long id) {
        Concert concert = concertMapper.selectById(id);
        if (concert == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "演出不存在");
        }
        return concert;
    }

    private void checkAdmin(CurrentUser operator) {
        permissionService.checkRole(operator, Role.ADMIN);
    }
}
