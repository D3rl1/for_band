package com.forband.concert;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.forband.auth.CurrentUser;
import com.forband.auth.PermissionService;
import com.forband.auth.Role;
import com.forband.common.BusinessException;
import com.forband.common.ErrorCode;
import com.forband.common.PageResult;
import com.forband.concert.dto.ConcertDtos.CastItem;
import com.forband.concert.dto.ConcertDtos.CastRequest;
import com.forband.concert.dto.ConcertDtos.CastRow;
import com.forband.concert.dto.ConcertDtos.ConcertDetailView;
import com.forband.concert.dto.ConcertDtos.ConcertRequest;
import com.forband.concert.dto.ConcertDtos.ConcertView;
import com.forband.concert.dto.ConcertDtos.PhotoRequest;
import com.forband.concert.dto.ConcertDtos.PhotoRow;
import com.forband.concert.dto.ConcertDtos.ProgramItem;
import com.forband.concert.dto.ConcertDtos.ProgramRequest;
import com.forband.concert.dto.ConcertDtos.ProgramRow;
import com.forband.file.FileService;
import com.forband.file.StoredFile;
import com.forband.member.Member;
import com.forband.member.MemberMapper;
import com.forband.piece.Piece;
import com.forband.piece.PieceMapper;
import com.forband.piece.PieceStatus;
import com.forband.section.Section;
import com.forband.section.SectionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 演出档案（docs/07）。**可见性：全团可见，含历史演出**（含荣誉团员）。
 * 增删改（含曲目单、参演名单）＝管理员 / 团长 / 指挥；**照片上传是全员**（演出当天拍照的往往是普通团员）。
 */
@Service
public class ConcertService {

    private final ConcertMapper concertMapper;
    private final ConcertCastMapper castMapper;
    private final ConcertPieceMapper programMapper;
    private final ConcertPhotoMapper photoMapper;
    private final PieceMapper pieceMapper;
    private final MemberMapper memberMapper;
    private final SectionMapper sectionMapper;
    private final FileService fileService;
    private final PermissionService permissionService;

    public ConcertService(ConcertMapper concertMapper, ConcertCastMapper castMapper,
                          ConcertPieceMapper programMapper, ConcertPhotoMapper photoMapper,
                          PieceMapper pieceMapper, MemberMapper memberMapper,
                          SectionMapper sectionMapper, FileService fileService,
                          PermissionService permissionService) {
        this.concertMapper = concertMapper;
        this.castMapper = castMapper;
        this.programMapper = programMapper;
        this.photoMapper = photoMapper;
        this.pieceMapper = pieceMapper;
        this.memberMapper = memberMapper;
        this.sectionMapper = sectionMapper;
        this.fileService = fileService;
        this.permissionService = permissionService;
    }

    public PageResult<ConcertView> page(int page, int size) {
        Page<Concert> result = concertMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<Concert>()
                        .orderByDesc(Concert::getDate).orderByDesc(Concert::getId));
        List<ConcertView> views = result.getRecords().stream().map(this::toView).toList();
        return PageResult.of(views, result.getTotal(), page, size);
    }

    public ConcertDetailView detail(long id) {
        Concert concert = requireConcert(id);
        return new ConcertDetailView(toView(concert), programOf(id), castOf(id), photosOf(id),
                concert.getSummary(), concert.getVideoUrl(), concert.getNote());
    }

    @Transactional
    public ConcertView create(ConcertRequest request, CurrentUser operator) {
        checkEditRole(operator);
        Concert concert = new Concert();
        apply(concert, request, ConcertStatus.PLANNED);
        concert.setCreatedBy(operator.memberId());
        concertMapper.insert(concert);
        return toView(concert);
    }

    @Transactional
    public ConcertView update(long id, ConcertRequest request, CurrentUser operator) {
        checkEditRole(operator);
        Concert concert = requireConcert(id);
        apply(concert, request, normalizeStatus(request.status(), concert.getStatus()));
        concert.setUpdatedBy(operator.memberId());
        concertMapper.updateById(concert);
        return toView(concert);
    }

    // ---------- 曲目单（整体替换，docs/07 §7.4：曲目**从曲目库选**，不自由填写） ----------

    @Transactional
    public List<ProgramRow> setProgram(long concertId, ProgramRequest request, CurrentUser operator) {
        checkEditRole(operator);
        requireConcert(concertId);
        programMapper.delete(new LambdaQueryWrapper<ConcertPiece>()
                .eq(ConcertPiece::getConcertId, concertId));
        List<ProgramItem> items = request.items() == null ? List.of() : request.items();
        int order = 1;
        for (ProgramItem item : items) {
            if (item.pieceId() == null) {
                continue;
            }
            Piece piece = pieceMapper.selectById(item.pieceId());
            if (piece == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "曲目单里的曲目不在曲目库中（id=" + item.pieceId() + "）");
            }
            ConcertPiece row = new ConcertPiece();
            row.setConcertId(concertId);
            row.setPieceId(piece.getId());
            row.setOrderNo(order++);
            row.setConductor(item.conductor());   // 指挥在曲目行上，每首可以不同
            row.setNote(item.note());
            programMapper.insert(row);
        }
        return programOf(concertId);
    }

    // ---------- 参演名单（整体替换） ----------

    @Transactional
    public List<CastRow> setCast(long concertId, CastRequest request, CurrentUser operator) {
        checkEditRole(operator);
        requireConcert(concertId);
        castMapper.delete(new LambdaQueryWrapper<ConcertCast>().eq(ConcertCast::getConcertId, concertId));
        List<CastItem> items = request.members() == null ? List.of() : request.members();
        for (CastItem item : items) {
            if (item.memberId() == null) {
                continue;
            }
            // ⚠️ **不校验 status**：已毕业 / 休团的人也能进名单（"毕业了回团参演"很常见）
            if (memberMapper.selectById(item.memberId()) == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "名单里的人不存在");
            }
            ConcertCast row = new ConcertCast();
            row.setConcertId(concertId);
            row.setMemberId(item.memberId());
            row.setNote(item.note());
            castMapper.insert(row);
        }
        return castOf(concertId);
    }

    // ---------- 照片（**登录全员都能传**，删除限本人 / 管理员） ----------

    @Transactional
    public PhotoRow addPhoto(long concertId, PhotoRequest request, CurrentUser uploader) {
        requireConcert(concertId);
        StoredFile file = fileService.require(request.fileId());
        ConcertPhoto row = new ConcertPhoto();
        row.setConcertId(concertId);
        row.setFileId(file.getId());
        row.setCaption(request.caption());
        row.setUploadedBy(uploader.memberId());
        row.setUploadedAt(LocalDateTime.now());
        photoMapper.insert(row);
        return new PhotoRow(row.getId(), file.getId(), row.getCaption(), uploader.name(),
                row.getUploadedAt());
    }

    /** 删照片：**上传者本人 / 管理员**（docs/07 §7.3）。只删关联行，`file` 行不删。 */
    @Transactional
    public void deletePhoto(long concertId, long photoId, CurrentUser operator) {
        ConcertPhoto photo = photoMapper.selectById(photoId);
        if (photo == null || photo.getConcertId() != concertId) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "照片不存在");
        }
        boolean owner = photo.getUploadedBy() != null && photo.getUploadedBy() == operator.memberId();
        if (!owner && operator.role() != Role.ADMIN) {
            throw new BusinessException(ErrorCode.NO_PERMISSION);
        }
        photoMapper.deleteById(photoId);
    }

    // ---------- 内部 ----------

    private void checkEditRole(CurrentUser operator) {
        permissionService.checkRole(operator, Role.ADMIN, Role.LEADER, Role.CONDUCTOR);
    }

    private Concert requireConcert(long id) {
        Concert concert = concertMapper.selectById(id);
        if (concert == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "演出不存在");
        }
        return concert;
    }

    private String normalizeStatus(String status, String fallback) {
        if (status == null || status.isBlank()) {
            return fallback;
        }
        if (!ConcertStatus.isValid(status)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "演出状态只能是 PLANNED / DONE / CANCELED");
        }
        return status;
    }

    private void apply(Concert concert, ConcertRequest request, String status) {
        concert.setName(request.name());
        concert.setDate(request.date());
        concert.setStartTime(request.startTime());
        concert.setVenue(request.venue());
        concert.setHost(request.host());
        concert.setPosterFileId(request.posterFileId());
        concert.setVideoUrl(request.videoUrl());
        concert.setSummary(request.summary());
        concert.setNote(request.note());
        concert.setStatus(status);
    }

    private ConcertView toView(Concert concert) {
        long id = concert.getId();
        return new ConcertView(id, concert.getName(), concert.getDate(), concert.getStartTime(),
                concert.getVenue(), concert.getHost(), concert.getStatus(),
                ConcertStatus.label(concert.getStatus()), concert.getPosterFileId(),
                count(programMapper, new LambdaQueryWrapper<ConcertPiece>()
                        .eq(ConcertPiece::getConcertId, id)),
                count(castMapper, new LambdaQueryWrapper<ConcertCast>()
                        .eq(ConcertCast::getConcertId, id)),
                count(photoMapper, new LambdaQueryWrapper<ConcertPhoto>()
                        .eq(ConcertPhoto::getConcertId, id)),
                Boolean.TRUE.equals(concert.getIsShowcase()));
    }

    private static <T> Integer count(com.baomidou.mybatisplus.core.mapper.BaseMapper<T> mapper,
                                     LambdaQueryWrapper<T> wrapper) {
        Long n = mapper.selectCount(wrapper);
        return n == null ? 0 : n.intValue();
    }

    private List<ProgramRow> programOf(long concertId) {
        List<ConcertPiece> rows = programMapper.selectList(new LambdaQueryWrapper<ConcertPiece>()
                .eq(ConcertPiece::getConcertId, concertId)
                .orderByAsc(ConcertPiece::getOrderNo).orderByAsc(ConcertPiece::getId));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, Piece> pieces = pieceMapper.selectBatchIds(
                        rows.stream().map(ConcertPiece::getPieceId).distinct().toList()).stream()
                .collect(Collectors.toMap(Piece::getId, Function.identity()));
        return rows.stream().map(row -> {
            Piece piece = pieces.get(row.getPieceId());
            return new ProgramRow(row.getPieceId(),
                    piece == null ? null : piece.getTitle(),
                    piece == null ? null : piece.getComposer(),
                    piece == null ? null : piece.getArranger(),
                    piece == null ? null : piece.getCategory(),
                    piece == null ? null : piece.getStatus(),
                    piece == null ? null : PieceStatus.label(piece.getStatus()),
                    row.getOrderNo() == null ? 0 : row.getOrderNo(),
                    row.getConductor(), row.getNote());
        }).toList();
    }

    private List<CastRow> castOf(long concertId) {
        List<ConcertCast> rows = castMapper.selectList(new LambdaQueryWrapper<ConcertCast>()
                .eq(ConcertCast::getConcertId, concertId).orderByAsc(ConcertCast::getMemberId));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, Member> members = memberMapper.selectBatchIds(
                        rows.stream().map(ConcertCast::getMemberId).distinct().toList()).stream()
                .collect(Collectors.toMap(Member::getId, Function.identity()));
        Map<Long, String> sections = sectionNamesOf(members.values().stream()
                .map(Member::getSectionId).toList());
        return rows.stream().map(row -> {
            Member member = members.get(row.getMemberId());
            return new CastRow(row.getMemberId(),
                    member == null ? null : member.getName(),
                    member == null || member.getSectionId() == null ? null
                            : sections.get(member.getSectionId()),
                    member == null ? null : member.getRole(),
                    member == null ? null : this.roleLabel(member.getRole()),
                    member == null ? null : member.getStatus(),
                    row.getNote());
        }).toList();
    }

    private List<PhotoRow> photosOf(long concertId) {
        List<ConcertPhoto> rows = photoMapper.selectList(new LambdaQueryWrapper<ConcertPhoto>()
                .eq(ConcertPhoto::getConcertId, concertId).orderByDesc(ConcertPhoto::getId));
        Map<Long, String> uploaders = rows.isEmpty() ? Map.of()
                : memberMapper.selectBatchIds(rows.stream().map(ConcertPhoto::getUploadedBy)
                        .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Member::getId, Member::getName));
        return rows.stream().map(row -> new PhotoRow(row.getId(), row.getFileId(), row.getCaption(),
                uploaders.get(row.getUploadedBy()), row.getUploadedAt())).toList();
    }

    private Map<Long, String> sectionNamesOf(List<Long> sectionIds) {
        List<Long> distinct = sectionIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return sectionMapper.selectBatchIds(distinct).stream()
                .collect(Collectors.toMap(Section::getId, Section::getName, (a, b) -> a));
    }

    private String roleLabel(String role) {
        return switch (role == null ? "" : role) {
            case "ADMIN" -> "管理员";
            case "LEADER" -> "团长";
            case "CONDUCTOR" -> "指挥";
            case "SECTION_LEADER" -> "声部长";
            case "MEMBER" -> "团员";
            case "HONORARY" -> "荣誉团员";
            default -> role;
        };
    }
}
