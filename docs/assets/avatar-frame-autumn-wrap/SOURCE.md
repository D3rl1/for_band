# 秋日树框来源与文件清单

- 选定日期：2026-11-16。版本为树高于头像、包裹左侧的 `autumn-wrap`，保留完整落叶动效。
- 原图由本会话的 OpenAI 内置 image_gen 根据用户的秋树 / 地面落叶描述从零生成；未使用第三方角色或外部参考图。不补写未提供的模型版本、作者署名或第三方许可。
- 原图 SHA256 见下表 `assets/source-original.png`。没有采用后来收窄树冠的重绘版本。
- 透明树 / 叶片由浏览器从原图的透明区域分离，静帧保持原构图；WebP 编码用于网页体积优化。组件无需运行时分割整张原图。
- 本目录是素材归档与渲染参考，不在 Spring Boot 的运行资源目录中。正式上线后按仓库唯一资源规则迁移到获准的运行位置，避免维护两份。
- 本轮未增加正式头像框清单、接口、成员字段或使用资格。工作标识 / 工作名不代表正式定稿文案。

| 文件 | 字节 | SHA256 |
|---|---:|---|
| `assets/leaf-1.webp` | 4016 | `e743090edadc51c0b553495c08713a95b3dde2a7935430f2a58e8d43177391f3` |
| `assets/leaf-2.webp` | 6586 | `e484bb07fccb54e28e78d2ab6b628011a2a3176a172140ca9fefb1c1cc57dba9` |
| `assets/leaf-3.webp` | 5142 | `d8b79ec9373758c53e1ce9c158dc7ae121793ddb32091f48507525a6cba905d5` |
| `assets/leaf-4.webp` | 5304 | `7f5dceec19b46bc1be0c2c8e77d071f72774f4aaac89b512655d4df98a6b9225` |
| `assets/leaf-5.webp` | 4116 | `9524e3b4b4a5e25f245139b49db3119bc3d16fad8a64a948fd6cae1cb430e1e1` |
| `assets/preview-manifest.json` | 665 | `14c2d068fdb14bd1aa24efaae108006007b09301a92cd80fd8d863db38d1f9b0` |
| `assets/source-original.png` | 1438215 | `1688342e68a6e62d1dbe8f2dfeb4614b54d9fc9042e8dcea25209c422364b5f3` |
| `assets/still.webp` | 444910 | `957f2233f36e91c16931777c4ee7422647910c946d753f688ca5fef6080cf846` |
| `assets/tree.webp` | 459834 | `412cefc52b068c34aa4b94f86a719f408b20be2c8b7bbba0a7425f57e374a526` |
| `checks.json` | 242 | `7a368dce56eefe4981d8c77c1d8f9f02c359e1dae645ac1abd17d140498d843e` |
| `README.md` | 5653 | `b8c64856b3334ec32b3b87a0cf0ce9f1ac6de6f37e6e95b352f8bb9f2f832f82` |
| `reference/autumn-wrap.css` | 637 | `ecb11f5a09a4e8bd330d3746e3690c03d870f35bb977b30939feb0dcc3f709ea` |
| `reference/autumn-wrap.js` | 5163 | `25cf666a6ea35e15c1cb80d732647b114420c9809008645348d454fce8a7bd98` |
