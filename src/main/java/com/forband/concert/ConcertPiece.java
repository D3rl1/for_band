package com.forband.concert;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 演出曲目单（docs/13 §13.3⑭）：**曲目从曲目库选，不自由填写**（否则曲目库就失去意义）。
 * **指挥放在曲目行上**——协奏曲请外聘指挥、某首由学生指挥，都能表达。
 */
@TableName("concert_piece")
public class ConcertPiece {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long concertId;

    private Long pieceId;

    /** 演出顺序。 */
    private Integer orderNo;

    /** 这首的指挥（可能与别的曲目不同；学生指挥 / 外聘指挥都能写）。 */
    private String conductor;

    private String note;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getConcertId() {
        return concertId;
    }

    public void setConcertId(Long concertId) {
        this.concertId = concertId;
    }

    public Long getPieceId() {
        return pieceId;
    }

    public void setPieceId(Long pieceId) {
        this.pieceId = pieceId;
    }

    public Integer getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(Integer orderNo) {
        this.orderNo = orderNo;
    }

    public String getConductor() {
        return conductor;
    }

    public void setConductor(String conductor) {
        this.conductor = conductor;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
