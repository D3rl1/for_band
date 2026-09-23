# fluentui-emoji（预置头像库，自托管）

本目录是**第三方前端资源**，按 `DECISIONS.md` D7 自托管、随仓库一起走，**不引用外部 CDN**。
用途与规矩见 `docs/14-前端资源与样式规范.md` §14.6.4「预置头像库」；
`member.avatar` 存的是**受控标识符**（本目录 `manifest.json` 里的 `id`，如 `fluent-frog`），
**不是 URL**——服务端校验 `值 ∈ 清单 ∪ {null}`，这是"零安全面"的关键。

> ⚠️ **仓库里只允许存在这一份。** 一个库一个目录、**一个库只存一份**（`AGENTS.md` 前端资源三条），
> 不许再平铺第二份。
>
> ⚠️ **素材不许改造**：本目录的 SVG 是上游原文件，**不改内容**——
> 改了就废掉"本地文件 = 上游哈希"这条校验。头像的圆形、底色、描边一律由 `band.css` 那边控制。

## 版本

**`microsoft/fluentui-emoji`**（MIT），**Color** 风格的单文件 SVG。

| 项 | 值 |
|---|---|
| 上游仓库 | https://github.com/microsoft/fluentui-emoji |
| 入库时的 `main` HEAD commit | `1ffb34c752ecf5d402f04cfb4b392c77f57c54bc` |
| 抽样路径的最近一次提交 | `e8a613a0baedbca8f144fa70950714f2583318a8`（`assets/Frog/Color/frog_color.svg`，2022-08-06） |
| 许可 | **MIT**（Copyright (c) Microsoft Corporation），全文见本目录 `LICENSE` |
| 下载日期 | 2026-09-23 |

> 记 **commit SHA** 比"三源一致"更强：它本身就是内容指纹与版本锚点（`docs/14` §14.6.4）。

## 文件清单（逐文件 SHA256）

路径约定：上游 `assets/<名字>/Color/<名字小写>_color.svg` → 入库为 `<标识符>.svg`，
**标识符 = 文件名去掉后缀**，也就是 `manifest.json` 里 `member.avatar` 的合法取值。

| 标识符 | 上游路径 | 大小 | SHA256 |
|---|---|---|---|
| `fluent-frog` | `assets/Frog/Color/frog_color.svg` | 5,062 B | `45677b96d78a3bd3915791bd75b18772fa02de1d400768c74d6e6788417bab96` |
| `fluent-cat` | `assets/Cat/Color/cat_color.svg` | 28,278 B | `302446b5011b874cfed0a5ad9c570fb839976dda201c6618af57a16a110fb8c1` |
| `fluent-panda` | `assets/Panda/Color/panda_color.svg` | 21,929 B | `324df7304eddd6e4bf7027fd19aa1c5c62de52984ccaaef6b265acdd96194956` |
| `fluent-rabbit` | `assets/Rabbit/Color/rabbit_color.svg` | 41,870 B | `1042369009c0720d236b6f056478661cb47ca5aa17444c093e974b266eca9ff2` |
| `fluent-tiger` | `assets/Tiger/Color/tiger_color.svg` | 32,983 B | `cf8ac4d80cb0fbb444ecd0e08feac9fb1cace4355c4708581bb52dabbde290ec` |
| `fluent-fox` | `assets/Fox/Color/fox_color.svg` | 35,209 B | `83292e5983c00597ab3c08e6ceb6a4e66f54df9c5e0c850aa9ea4c5bc8621013` |
| `fluent-bear` | `assets/Bear/Color/bear_color.svg` | 26,330 B | `b75ab3d7d4af67458f39f4d8569af9e0a0ba9b8b28dd706d0e5e280565a5ec38` |
| `fluent-tangerine` | `assets/Tangerine/Color/tangerine_color.svg` | 6,478 B | `93a761b3dc9cda1fb5d753e0034aae06ae8650176d3c4c335f7ff1c7a0c6b65c` |
| `fluent-violin` | `assets/Violin/Color/violin_color.svg` | 88,146 B | `0b2670821957ba142a9238004f165aeda1896a97a1b16a55c04f4fca0f9ac518` |
| `fluent-guitar` | `assets/Guitar/Color/guitar_color.svg` | 13,355 B | `1d9601d86301b0878e5472a1fa00701b322220b918054019c6a4faf99b0bd40b` |
| `fluent-drum` | `assets/Drum/Color/drum_color.svg` | 26,265 B | `d46aee793e4265906005f89935b43131de963b2bd5dba8e108ab6667666c556e` |
| `fluent-microphone` | `assets/Microphone/Color/microphone_color.svg` | 7,272 B | `c2226e4dfcfd720afe892c5d7d0d0d7c2004835a2dd095b11e77732bfc19a553` |
| `LICENSE` | 上游 `LICENSE`（MIT） | 1,141 B | `c2cfccb812fe482101a8f04597dfc5a9991a6b2748266c47ac91b6a5aae15383` |

> 形态实测：每个 emoji 的 Color 风格都是**单文件 SVG**（`viewBox="0 0 72 72"`），
> **能直接 `<img src>` 引用、不用缩放就是 `--av`(52px) 那个圆**（零处理、零模糊）。
> ⚠️ 体积差别不小（`frog` 5 KB ↔ `violin` 88 KB）：越写实的图案路径越多，属正常。

## 来源与校验

下载日期：2026-09-23。**逐文件从两个独立来源各取一份，SHA256 全部一致才入库**：

- `https://cdn.jsdelivr.net/gh/microsoft/fluentui-emoji@main/assets/...`
- `https://raw.githubusercontent.com/microsoft/fluentui-emoji/main/assets/...`

**多源比对是刻意的**：单一来源若被投毒，哈希不会自己暴露问题；两源不一致时不许入库，先查明原因。
（第三次尝试的 `cdn.statically.io` 与 `raw.githack.com` 在当前网络下连不通，实际只用了上面两源。
另：`raw` 在批量取的时候偶发返回空文件，**重试后与 `jsdelivr` 一致**——所以是逐文件比对，不是"取一次就算过"。）

交叉核对：`docs/14` §14.6.4 记录的实测值「`Frog/Color/frog_color.svg` = **5,062 字节**」与本次入库的
`fluent-frog.svg`（5,062 B）**完全一致**，可佐证取到的就是当初规格实测的那一份。

## 怎么引用

`static/` 由 Spring Boot 默认挂在根路径，所以前端直接写：

```html
<img src="/vendor/fluent-emoji/fluent-frog.svg" alt="">
```

- **清单**：`/vendor/fluent-emoji/manifest.json`——**服务端（`AvatarCatalog`）与前端读同一份**；
- **回落规则**：`member.avatar` 为空 → 界面**回退姓名首字**（`docs/14` §14.6.4）；
- **展示范围**：本期只换**已有的三处**（侧栏头像行 / 个人栏 / 欢迎过渡落点）+ 个人页自己那份。
  **名册 / 考勤明细 / 演出名单本期不加头像**（会牵动表格布局，另开一轮）。

## 换素材 / 升级怎么做

1. 重新从**两个以上独立来源**取目标 SVG，**SHA256 一致**；
2. 替换文件 → 更新本文件的**表格与哈希** + `main` HEAD commit；
3. 清单增删要同时改 `manifest.json`（**服务端白名单跟着它走，不用改代码**）；
4. 新增头像走 `docs/00` 的暂定项流程另议（若涉及形象风格调整）。
