package com.forband.member;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forband.common.CsvWriter;
import com.forband.common.SectionLookup;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 全部账号的激活状态表：未激活显示初始密码，已激活显示「已激活」（docs/01）。 */
@Component
public class AccountCsvExporter {

    private final MemberMapper memberMapper;
    private final SectionLookup sectionLookup;

    public AccountCsvExporter(MemberMapper memberMapper, SectionLookup sectionLookup) {
        this.memberMapper = memberMapper;
        this.sectionLookup = sectionLookup;
    }

    public String export() {
        List<Member> members = memberMapper.selectList(new LambdaQueryWrapper<Member>()
                .isNotNull(Member::getAccount)
                .orderByAsc(Member::getSectionId));
        Map<Long, String> sectionNames = sectionLookup.sectionNamesOf(members.stream()
                .map(Member::getSectionId).toList());
        StringBuilder csv = new StringBuilder(CsvWriter.BOM + "姓名,声部,账号,初始密码,状态\r\n");
        for (Member member : members) {
            String section = member.getSectionId() == null ? "" : sectionNames.getOrDefault(member.getSectionId(), "");
            String password = member.getInitialPassword() == null ? "已激活" : member.getInitialPassword();
            csv.append(CsvWriter.escape(member.getName())).append(',')
                    .append(CsvWriter.escape(section)).append(',')
                    .append(CsvWriter.escape(member.getAccount())).append(',')
                    .append(CsvWriter.escape(password)).append(',')
                    .append(CsvWriter.escape(member.getStatus())).append("\r\n");
        }
        return csv.toString();
    }
}
