package com.forband.recording;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.forband.auth.CurrentUser;
import com.forband.auth.PermissionService;
import com.forband.auth.Role;
import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.common.PageResult;
import com.forband.file.FileService;
import com.forband.file.StoredFile;
import com.forband.member.Member;
import com.forband.member.MemberMapper;
import com.forband.piece.PieceMapper;
import com.forband.recording.dto.RecordingDtos.CommentRequest;
import com.forband.recording.dto.RecordingDtos.CommentView;
import com.forband.recording.dto.RecordingDtos.CreateRequest;
import com.forband.recording.dto.RecordingDtos.RecordingDetailView;
import com.forband.recording.dto.RecordingDtos.RecordingRow;
import com.forband.section.Section;
import com.forband.section.SectionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 练习录音（docs/08 §8.3/§8.4）。
 * <p>
 * **可见性一律走 {@link RecordingVisibility}**（列表在查询层收口、详情与点评再判一次、
 * 文件流由 {@link RecordingFileAccessRule} 复用同一条）——这是本模块唯一的权限来源。
 * <p>
 * 时长上限 3 分钟（暂定 T5）**只能在前端拦**：服务端不解析音频（不引库、不转码），
 * 所以这里只校验格式（文件接口按文件头嗅探）与大小。想真拦住得在服务端解析音频，本期不做。
 */
@Service
public class RecordingService {

    private final RecordingMapper recordingMapper;
    private final RecordingCommentMapper commentMapper;
    private final RecordingVisibility visibility;
    private final FileService fileService;
    private final PieceMapper pieceMapper;
    private final MemberMapper memberMapper;
    private final SectionMapper sectionMapper;
    private final PermissionService permissionService;

    public RecordingService(RecordingMapper recordingMapper, RecordingCommentMapper commentMapper,
                            RecordingVisibility visibility, FileService fileService,
                            PieceMapper pieceMapper, MemberMapper memberMapper,
                            SectionMapper sectionMapper, PermissionService permissionService) {
        this.recordingMapper = recordingMapper;
        this.commentMapper = commentMapper;
        this.visibility = visibility;
        this.fileService = fileService;
        this.pieceMapper = pieceMapper;
        this.memberMapper = memberMapper;
        this.sectionMapper = sectionMapper;
        this.permissionService = permissionService;
    }

    /**
     * 列表（docs/08 §8.3）——**可见性在查询层收口**，不是过滤内存（分页会骗人）：
     * 能看全部的不过滤；声部长按"本声部 or 自己"；**其余角色默认只给自己**。
     * `memberId` / `sectionId` / `pieceId` 只是筛选条件，**缩不了权限**。
     */
    public PageResult<RecordingRow> page(Long memberId, Long sectionId, Long pieceId,
                                         int page, int size, CurrentUser viewer) {
        LambdaQueryWrapper<Recording> wrapper = new LambdaQueryWrapper<Recording>()
                .eq(memberId != null, Recording::getMemberId, memberId)
                .eq(sectionId != null, Recording::getSectionId, sectionId)
                .eq(pieceId != null, Recording::getPieceId, pieceId)
                .orderByDesc(Recording::getId);
        if (!visibility.seesAll(viewer)) {
            if (viewer.role() == Role.SECTION_LEADER && viewer.sectionId() != null) {
                wrapper.and(w -> w.eq(Recording::getSectionId, viewer.sectionId())
                        .or().eq(Recording::getMemberId, viewer.memberId()));
            } else {
                wrapper.eq(Recording::getMemberId, viewer.memberId());   // 默认拒绝：只给自己
            }
        }
        Page<Recording> result = recordingMapper.selectPage(Page.of(page, size), wrapper);
        List<RecordingRow> rows = result.getRecords().stream().map(this::toRow).toList();
        return PageResult.of(rows, result.getTotal(), page, size);
    }

    /** 详情（含点评）：**按 id 取数据**的接口 → 必须判可见性（`SECURITY.md` A2）。 */
    public RecordingDetailView detail(long id, CurrentUser viewer) {
        Recording row = requireViewable(id, viewer);
        return new RecordingDetailView(toRow(row), commentsOf(id));
    }

    @Transactional
    public RecordingRow create(CreateRequest request, CurrentUser uploader) {
        permissionService.checkNotHonorary(uploader);   // 荣誉团员"上传/修改/删除任何内容 ❌"
        if (request.fileId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少 fileId（先调 POST /api/files）");
        }
        StoredFile file = fileService.require(request.fileId());
        if (request.pieceId() != null && pieceMapper.selectById(request.pieceId()) == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "曲目不存在");
        }
        Recording row = new Recording();
        // ⚠️ 归属只认登录身份：客户端传什么都不看（DTO 里压根没有 memberId/sectionId 两个字段）
        row.setMemberId(uploader.memberId());
        row.setSectionId(uploader.sectionId());   // 冗余列；指挥/团长没有声部 → 为空
        row.setPieceId(request.pieceId());
        row.setTitle(request.title());
        row.setFileId(file.getId());
        row.setDurationSec(request.durationSec());
        row.setSizeBytes(file.getSizeBytes());
        row.setUploadedAt(LocalDateTime.now());
        recordingMapper.insert(row);
        return toRow(row);
    }

    /** 删录音：**管理员 / 本人 / 本声部长**（团长 / 指挥能听全部、**不删**，见 {@link RecordingVisibility#canDelete}）。 */
    @Transactional
    public void delete(long id, CurrentUser operator) {
        Recording row = recordingMapper.selectById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "录音不存在");
        }
        if (!visibility.canDelete(row, operator)) {
            throw new BusinessException(ErrorCode.NO_PERMISSION);
        }
        commentMapper.delete(new LambdaQueryWrapper<RecordingComment>()
                .eq(RecordingComment::getRecordingId, id));
        recordingMapper.deleteById(id);
    }

    /**
     * 留言点评：**能看见这条录音就能说话**（docs/08 §8.4 的"双向"——声部长留言、团员追问）。
     * 所以这里复用 {@link RecordingVisibility#canView}，不另写一套判定。
     */
    @Transactional
    public CommentView comment(long recordingId, CommentRequest request, CurrentUser author) {
        requireViewable(recordingId, author);
        RecordingComment row = new RecordingComment();
        row.setRecordingId(recordingId);
        row.setAuthorId(author.memberId());
        row.setContent(request.content());
        commentMapper.insert(row);
        return new CommentView(row.getId(), author.memberId(), author.name(), row.getContent(),
                row.getCreatedAt());
    }

    // ---------- 内部 ----------

    private Recording requireViewable(long id, CurrentUser viewer) {
        Recording row = recordingMapper.selectById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "录音不存在");
        }
        if (!visibility.canView(row, viewer)) {
            throw new BusinessException(ErrorCode.NO_PERMISSION);
        }
        return row;
    }

    private List<CommentView> commentsOf(long recordingId) {
        List<RecordingComment> rows = commentMapper.selectList(new LambdaQueryWrapper<RecordingComment>()
                .eq(RecordingComment::getRecordingId, recordingId)
                .orderByAsc(RecordingComment::getId));
        Map<Long, String> names = rows.isEmpty() ? Map.of()
                : memberMapper.selectBatchIds(rows.stream().map(RecordingComment::getAuthorId)
                        .distinct().toList()).stream()
                .collect(Collectors.toMap(Member::getId, Member::getName));
        return rows.stream().map(row -> new CommentView(row.getId(), row.getAuthorId(),
                names.get(row.getAuthorId()), row.getContent(), row.getCreatedAt())).toList();
    }

    private RecordingRow toRow(Recording row) {
        Member member = memberMapper.selectById(row.getMemberId());
        Section section = row.getSectionId() == null ? null : sectionMapper.selectById(row.getSectionId());
        com.forband.piece.Piece piece = row.getPieceId() == null ? null
                : pieceMapper.selectById(row.getPieceId());
        String pieceTitle = piece == null ? null : piece.getTitle();
        Long comments = commentMapper.selectCount(new LambdaQueryWrapper<RecordingComment>()
                .eq(RecordingComment::getRecordingId, row.getId()));
        return new RecordingRow(row.getId(), row.getMemberId(),
                member == null ? null : member.getName(),
                section == null ? null : section.getName(),
                row.getPieceId(), pieceTitle, row.getTitle(), row.getFileId(),
                row.getDurationSec(), row.getSizeBytes(), row.getUploadedAt(),
                comments == null ? 0 : comments.intValue());
    }
}
