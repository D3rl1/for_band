package com.forband.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forband.member.Member;
import com.forband.member.MemberMapper;
import com.forband.member.MemberStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 首次启动播种管理员系统账号（is_system=1）。密码来自配置（application-local.yml / 环境变量），
 * 绝不写入日志。系统账号不进名册、不参与考勤统计（docs/13 不变式 3）。
 */
@Component
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final MemberMapper memberMapper;
    private final PasswordEncoder encoder;

    @Value("${band.admin.account:}")
    private String account;

    @Value("${band.admin.password:}")
    private String password;

    public AdminSeeder(MemberMapper memberMapper, PasswordEncoder encoder) {
        this.memberMapper = memberMapper;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long count = memberMapper.selectCount(
                new LambdaQueryWrapper<Member>().eq(Member::getIsSystem, true));
        if (count != null && count > 0) {
            return;
        }
        if (account.isBlank() || password.isBlank()) {
            throw new IllegalStateException(
                    "缺少 band.admin.account / band.admin.password 配置，无法创建管理员系统账号");
        }
        if (password.length() < 8) {
            throw new IllegalStateException("band.admin.password 至少 8 位");
        }
        Member admin = new Member();
        admin.setName("管理员");
        admin.setRole(Role.ADMIN.name());
        admin.setStatus(MemberStatus.ACTIVE);
        admin.setAccount(account);
        admin.setPasswordHash(encoder.encode(password));
        admin.setIsSystem(true);
        admin.setJoinedAt(LocalDate.now());
        memberMapper.insert(admin);
        log.info("已创建管理员系统账号（账号名：{}）", account);
    }
}
