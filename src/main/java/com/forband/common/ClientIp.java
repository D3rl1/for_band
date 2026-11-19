package com.forband.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端真实 IP：反代场景下取 X-Real-IP，否则只用 getRemoteAddr()。
 * docs/12 §12.6 的 Nginx 配置明设 `X-Real-IP $remote_addr`，注释点名"登录设备列表要显示真实 IP"；
 * 走反代后不读这个头的话，后端拿到的全是 127.0.0.1（登录限流的 IP 维度也会失效）。
 * <p>
 * ⚠️ 这个头**只在请求确实来自反代时才可信**（SECURITY.md B10）：Nginx 与本应用同机
 * （`proxy_pass http://127.0.0.1:8080`），所以回代转发的请求 `getRemoteAddr()` 必然是环回地址。
 * 无条件采信的话，直连 `:8080` 的客户端自己填一个头就是一个新的计数桶，
 * **IP 维度的登录防爆破等于没有**。故只认环回来源送来的该头。
 * <p>
 * 前提：应用端口不对公网开放（docs/12）。
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (isLoopback(remote)) {
            String real = request.getHeader("X-Real-IP");
            if (real != null && !real.isBlank()) {
                return real.trim();
            }
        }
        return remote;
    }

    private static boolean isLoopback(String addr) {
        return "127.0.0.1".equals(addr)
                || "::1".equals(addr)
                || "0:0:0:0:0:0:0:1".equals(addr);
    }
}
