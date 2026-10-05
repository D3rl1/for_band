package com.forband.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 全局唯一的密码编码器。原先 auth / admin-seeder / member 三处各自 {@code new BCryptPasswordEncoder()}，
 * 收成一个 Bean（DECISIONS D6：不引入 Spring Security，只用它的 BCrypt 实现）。
 */
@Configuration
public class CryptoConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
