package com.forband.concert;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 演出参演名单（docs/13 §13.3⑬）：**联合主键**（concert_id + member_id）。
 * <p>
 * ⚠️ **荣誉团员可以被列入**（docs/07 §7.4：现实里"毕业了回团参演"很常见）——
 * 所以这里**不校验 status**，已毕业 / 休团的人都能进名单。三条限制照旧：
 * 不计考勤、不能自己报名或改名单、不能签到。
 */
@TableName("concert_cast")
public class ConcertCast {

    private Long concertId;

    private Long memberId;

    private String note;

    public Long getConcertId() {
        return concertId;
    }

    public void setConcertId(Long concertId) {
        this.concertId = concertId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public void setMemberId(Long memberId) {
        this.memberId = memberId;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
