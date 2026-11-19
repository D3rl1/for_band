package com.forband.concert;

import com.forband.auth.CurrentUser;
import com.forband.concert.dto.ConcertDtos.ConcertDetailView;
import com.forband.concert.dto.ConcertDtos.ProgramRow;
import org.springframework.stereotype.Component;

/**
 * 可打印节目单（docs/07 §7.4，待定项 T6）：**生成一张可打印的 HTML 页**，用于现场分发或发群。
 * <p>
 * ⚠️ 规格只写了"生成一张可打印的 HTML 页"、**没给字段清单**，也提到"复用作者另一个项目里的报社排版模板"
 * （那份模板不在本仓库）→ 这里用**项目自己的视觉令牌**另做一张，字段取打印场景需要的那些。
 * 两处留白都记进了 `移交事项-2026-09-23.md`。
 * <p>
 * 安全：所有外部文本一律转义（`SECURITY.md` C3），**不信任任何字段**。
 */
@Component
public class ProgramSheet {

    private final ConcertService concertService;

    public ProgramSheet(ConcertService concertService) {
        this.concertService = concertService;
    }

    public String render(long concertId, CurrentUser viewer) {
        ConcertDetailView detail = concertService.detail(concertId);   // 可见性：登录即可（全团可见）
        StringBuilder rows = new StringBuilder();
        int index = 1;
        for (ProgramRow item : detail.program()) {
            rows.append("<tr><td class=\"no\">").append(index++).append("</td><td>")
                    .append(esc(item.title())).append("</td><td>")
                    .append(esc(item.composer())).append("</td><td>")
                    .append(esc(item.conductor())).append("</td></tr>\n");
        }
        if (rows.isEmpty()) {
            rows.append("<tr><td colspan=\"4\" class=\"empty\">曲目单还没排</td></tr>\n");
        }
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                <meta charset="utf-8">
                <title>%s · 节目单</title>
                <style>
                  body { font-family: "Plex", "Songti SC", serif; color: #2A1D14; margin: 48px auto; max-width: 720px; }
                  h1 { font-size: 30px; letter-spacing: .08em; text-align: center; margin: 0 0 6px; }
                  .meta { text-align: center; color: #6B5B4E; font-size: 14px; margin-bottom: 34px; }
                  table { width: 100%%; border-collapse: collapse; font-size: 16px; }
                  th, td { padding: 10px 8px; border-bottom: 1px solid #E4DBD0; text-align: left; }
                  th { font-size: 13px; color: #6B5B4E; font-weight: 600; }
                  .no { width: 40px; color: #D9644F; }
                  .empty { color: #6B5B4E; text-align: center; padding: 30px 0; }
                  .tip { margin-top: 34px; text-align: center; font-size: 12px; color: #6B5B4E; }
                  @media print { .tip { display: none; } body { margin: 0; } }
                </style>
                </head>
                <body>
                <h1>%s</h1>
                <p class="meta">%s</p>
                <table>
                <thead><tr><th></th><th>曲目</th><th>作曲</th><th>指挥</th></tr></thead>
                <tbody>
                %s</tbody>
                </table>
                <p class="tip">按 Ctrl+P 打印或另存为 PDF</p>
                </body>
                </html>
                """.formatted(esc(detail.concert().name()), esc(detail.concert().name()),
                esc(metaOf(detail)), rows);
    }

    private String metaOf(ConcertDetailView detail) {
        return java.util.stream.Stream.of(
                        detail.concert().date() == null ? null : detail.concert().date().toString(),
                        detail.concert().startTime() == null ? null
                                : detail.concert().startTime().toString().substring(0, 5),
                        detail.concert().venue(),
                        detail.concert().host() == null ? null : "主办：" + detail.concert().host())
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.joining(" · "));
    }

    private static String esc(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
