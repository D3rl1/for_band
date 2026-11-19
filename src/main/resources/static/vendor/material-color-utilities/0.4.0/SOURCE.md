# material-color-utilities（自托管）

主题设置页（`docs/14` §14.6.2c / #244）的颜色引擎。按 `DECISIONS.md` D7 自托管，
**不引用 CDN**；仓库里只允许存在这一份（AGENTS.md 前端资源第 1 条）。

## 版本与来源

| 项 | 值 |
|---|---|
| npm 包 | `@material/material-color-utilities` |
| 版本 | **0.4.0** |
| 配色规范 | **2021**（`SchemeTonalSpot` 显式传 `'2021'`） |
| 包来源 | `https://registry.npmjs.org/@material/material-color-utilities/-/material-color-utilities-0.4.0.tgz` |
| 上游仓库 | `https://github.com/material-foundation/material-color-utilities` |
| 许可证 | **Apache-2.0**（全文见同目录 `LICENSE`） |

## 产物

| 文件 | 说明 | 大小 | SHA-256 |
|---|---|---|---|
| `index.iife.js` | 浏览器直接加载的 IIFE，全局名 **`MCU`** | 89,109 B | `a249b1bed3e817dde5799b7ac1704aadd8149d54f78edd0df0886dc2f2b9f921` |
| `LICENSE` | 上游 Apache-2.0 许可证原文 | 11,357 B | `8ded460ad5bd3ab9082eaa2f7502cc9bb621eaff87cecc55d823f21758707cea` |

`index.iife.js` 是构建产物，不是 npm 包里的原始文件。构建方式：

```js
// entry.js
import {
  DynamicScheme, Hct, MaterialDynamicColors, SchemeTonalSpot, TonalPalette,
  argbFromHex, hexFromArgb,
} from '@material/material-color-utilities';
export { DynamicScheme, Hct, MaterialDynamicColors, SchemeTonalSpot, TonalPalette, argbFromHex, hexFromArgb };
```

```bash
npm install @material/material-color-utilities@0.4.0 esbuild@0.28.2
npx esbuild entry.js --bundle --format=iife --global-name=MCU --minify --outfile=index.iife.js
```

只导出页面实际用到的 7 个符号；其余模块被 tree-shake 掉。
**没有 source map**（保持单文件、便于校验哈希）。

## 校验

2026-11-04 构建后与样张 `design-samples/样张-主题设置-244.html` 的内联包逐值核对：
种子 `#1F6F5C` / 浅色 / 对比度 0 的 `primary = #0F6B57`、`surface = #F5FBF6`、
`onSurface = #171D1B`、`outline = #6F7975`、`neutral tone 12 = #1B211F` 全部一致。
页面只从 `SchemeTonalSpot(...)` 的角色取值，不读包内私有实现。

## 页面怎么引用

```html
<script src="/vendor/material-color-utilities/0.4.0/index.iife.js"></script>
```

**只在 `theme.html` 引入**：其他页面首帧恢复走 `band.theme.tokens.v2` 里已算好的
令牌缓存（`band-nav.js` 同步写入），不需要在每个页面加载整套引擎。
