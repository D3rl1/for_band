package com.forband.piece;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.forband.auth.CurrentUser;
import com.forband.auth.PermissionService;
import com.forband.auth.Role;
import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.common.PageResult;
import com.forband.event.Event;
import com.forband.event.EventMapper;
import com.forband.event.EventService;
import com.forband.file.FileService;
import com.forband.file.StoredFile;
import com.forband.member.Member;
import com.forband.member.MemberMapper;
import com.forband.piece.dto.PieceDtos.AttachmentView;
import com.forband.piece.dto.PieceDtos.EventPieceView;
import com.forband.piece.dto.PieceDtos.PieceDetailView;
import com.forband.piece.dto.PieceDtos.PieceRequest;
import com.forband.piece.dto.PieceDtos.PieceView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 曲目库（docs/06）。权限：**看**＝登录即可（含荣誉团员）；**增删改**＝管理员 / 团长 / 指挥（docs/02 §2.3）。
 * ⚠️ 曲目**本体没有删除接口**（docs/06 §6.3 只给了删附件）——`02` 矩阵写"曲目库增删改"的措辞与之不一致，
 * 已记进 `移交事项-2026-09-23.md`。
 */
@Service
public class PieceService {

    private final PieceMapper pieceMapper;
    private final PieceAttachmentMapper attachmentMapper;
    private final EventPieceMapper eventPieceMapper;
    private final EventMapper eventMapper;
    private final EventService eventService;
    private final FileService fileService;
    private final PermissionService permissionService;
    private final MemberMapper memberMapper;

    public PieceService(PieceMapper pieceMapper, PieceAttachmentMapper attachmentMapper,
                        EventPieceMapper eventPieceMapper, EventMapper eventMapper,
                        EventService eventService, FileService fileService,
                        PermissionService permissionService, MemberMapper memberMapper) {
        this.pieceMapper = pieceMapper;
        this.attachmentMapper = attachmentMapper;
        this.eventPieceMapper = eventPieceMapper;
        this.eventMapper = eventMapper;
        this.eventService = eventService;
        this.fileService = fileService;
        this.permissionService = permissionService;
        this.memberMapper = memberMapper;
    }

    /** 列表（docs/06 §6.3：`?status=&keyword=`，分页）。 */
    public PageResult<PieceView> page(String status, String keyword, int page, int size) {
        LambdaQueryWrapper<Piece> wrapper = new LambdaQueryWrapper<Piece>()
                .eq(status != null && !status.isBlank(), Piece::getStatus, status)
                .orderByAsc(Piece::getStatus).orderByDesc(Piece::getId);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(Piece::getTitle, kw).or().like(Piece::getComposer, kw));
        }
        Page<Piece> result = pieceMapper.selectPage(Page.of(page, size), wrapper);
        return PageResult.of(result.getRecords().stream().map(this::toView).toList(),
                result.getTotal(), page, size);
    }

    /** 详情：元数据 + 附件列表。 */
    public PieceDetailView detail(long id) {
        Piece piece = requirePiece(id);
        List<PieceAttachment> attachments = attachmentMapper.selectList(
                new LambdaQueryWrapper<PieceAttachment>()
                        .eq(PieceAttachment::getPieceId, id)
                        .orderByAsc(PieceAttachment::getId));
        List<Long> uploaderIds = attachments.stream().map(PieceAttachment::getUploadedBy)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> uploaders = uploaderIds.isEmpty() ? Map.of()
                : memberMapper.selectBatchIds(uploaderIds).stream()
                        .collect(Collectors.toMap(Member::getId, Member::getName));
        List<Long> fileIds = attachments.stream().map(PieceAttachment::getFileId).toList();
        Map<Long, StoredFile> files = fileIds.isEmpty() ? Map.of()
                : fileService.findAll(fileIds);

        List<AttachmentView> views = attachments.stream().map(a -> {
            StoredFile file = files.get(a.getFileId());
            return new AttachmentView(a.getId(), a.getKind(), a.getFileId(),
                    file == null ? null : file.getOriginalName(),
                    file == null ? null : file.getContentType(),
                    file == null || file.getSizeBytes() == null ? 0 : file.getSizeBytes(),
                    uploaders.get(a.getUploadedBy()), a.getUploadedAt());
        }).toList();
        return new PieceDetailView(toView(piece), views);
    }

    @Transactional
    public PieceView create(PieceRequest request, CurrentUser operator) {
        checkEditRole(operator);
        String status = normalizeStatus(request.status(), PieceStatus.CANDIDATE);
        Piece piece = new Piece();
        apply(piece, request, status);
        piece.setCreatedBy(operator.memberId());
        pieceMapper.insert(piece);
        return toView(piece);
    }

    @Transactional
    public PieceView update(long id, PieceRequest request, CurrentUser operator) {
        checkEditRole(operator);
        Piece piece = requirePiece(id);
        apply(piece, request, normalizeStatus(request.status(), piece.getStatus()));
        piece.setUpdatedBy(operator.memberId());
        pieceMapper.updateById(piece);
        return toView(piece);
    }

    /** 挂附件：`fileId` 必须先由 `POST /api/files` 上传得到（docs/08 §8.5 的统一机制）。 */
    @Transactional
    public AttachmentView attach(long pieceId, long fileId, String kind, CurrentUser operator) {
        checkEditRole(operator);
        requirePiece(pieceId);
        if (!AttachmentKind.isValid(kind)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "附件类型只能是 AUDIO / SCORE");
        }
        StoredFile file = fileService.require(fileId);   // 不存在 → 404

        PieceAttachment attachment = new PieceAttachment();
        attachment.setPieceId(pieceId);
        attachment.setKind(kind);
        attachment.setFileId(fileId);
        attachment.setUploadedBy(operator.memberId());
        attachment.setUploadedAt(LocalDateTime.now());
        attachmentMapper.insert(attachment);
        return new AttachmentView(attachment.getId(), kind, fileId, file.getOriginalName(),
                file.getContentType(), file.getSizeBytes() == null ? 0 : file.getSizeBytes(),
                operator.name(), attachment.getUploadedAt());
    }

    /** 删附件：**只删关联，不删 `file` 行**（同一份文件可能被别处引用，且留痕要记的是"谁删了关联"）。
     *  权限与挂附件一致（`docs/02` §2.3：曲目库增删改＝管理员 / 团长 / 指挥）。 */
    @Transactional
    public void detach(long pieceId, long attachmentId, CurrentUser operator) {
        checkEditRole(operator);
        PieceAttachment attachment = attachmentMapper.selectById(attachmentId);
        if (attachment == null || attachment.getPieceId() != pieceId) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "附件不存在");
        }
        attachmentMapper.deleteById(attachmentId);
    }

    // ---------- 活动关联曲目（docs/04 §4.5，期 3） ----------

    /** 这场活动练了哪些曲（给活动详情页用）。 */
    public List<EventPieceView> piecesOfEvent(long eventId) {
        List<EventPiece> links = eventPieceMapper.selectList(new LambdaQueryWrapper<EventPiece>()
                .eq(EventPiece::getEventId, eventId)
                .orderByAsc(EventPiece::getOrderNo).orderByAsc(EventPiece::getId));
        if (links.isEmpty()) {
            return List.of();
        }
        Map<Long, Piece> pieces = pieceMapper.selectBatchIds(
                        links.stream().map(EventPiece::getPieceId).distinct().toList()).stream()
                .collect(Collectors.toMap(Piece::getId, Function.identity()));
        return links.stream().map(link -> {
            Piece piece = pieces.get(link.getPieceId());
            return new EventPieceView(link.getPieceId(),
                    piece == null ? null : piece.getTitle(),
                    piece == null ? null : piece.getComposer(),
                    piece == null ? null : piece.getStatus(),
                    piece == null ? null : PieceStatus.label(piece.getStatus()),
                    link.getOrderNo(), link.getNote());
        }).toList();
    }

    /** 设置活动的曲目单（**整体替换**，与演出曲目单同一套做法）。 */
    @Transactional
    public List<EventPieceView> setEventPieces(long eventId, List<Long> pieceIds, CurrentUser operator) {
        Event event = eventMapper.selectById(eventId);
        if (event == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "活动不存在");
        }
        // 谁能改这场活动，谁就能改它的曲目单（复用活动自己的编辑权限判定）
        eventService.checkEditPermission(operator, event);

        eventPieceMapper.delete(new LambdaQueryWrapper<EventPiece>().eq(EventPiece::getEventId, eventId));
        List<Long> distinct = pieceIds == null ? List.of()
                : pieceIds.stream().filter(Objects::nonNull).distinct().toList();
        int order = 1;
        for (Long pieceId : distinct) {
            requirePiece(pieceId);
            EventPiece link = new EventPiece();
            link.setEventId(eventId);
            link.setPieceId(pieceId);
            link.setOrderNo(order++);
            eventPieceMapper.insert(link);
        }
        return piecesOfEvent(eventId);
    }

    // ---------- 内部 ----------

    private void checkEditRole(CurrentUser operator) {
        permissionService.checkRole(operator, Role.ADMIN, Role.LEADER, Role.CONDUCTOR);
    }

    private Piece requirePiece(long id) {
        Piece piece = pieceMapper.selectById(id);
        if (piece == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "曲目不存在");
        }
        return piece;
    }

    private String normalizeStatus(String status, String fallback) {
        if (status == null || status.isBlank()) {
            return fallback;
        }
        if (!PieceStatus.isValid(status)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "曲目状态只能是 CANDIDATE / REHEARSING / READY / PAUSED");
        }
        return status;
    }

    private void apply(Piece piece, PieceRequest request, String status) {
        piece.setTitle(request.title());
        piece.setComposer(request.composer());
        piece.setArranger(request.arranger());
        piece.setCategory(request.category());
        piece.setDifficulty(request.difficulty());
        piece.setDurationSec(request.durationSec());
        piece.setStatus(status);
        piece.setNote(request.note());
    }

    private PieceView toView(Piece piece) {
        return new PieceView(piece.getId(), piece.getTitle(), piece.getComposer(), piece.getArranger(),
                piece.getCategory(), piece.getDifficulty(), piece.getDurationSec(), piece.getStatus(),
                PieceStatus.label(piece.getStatus()), piece.getNote(), piece.getUpdatedAt());
    }
}
