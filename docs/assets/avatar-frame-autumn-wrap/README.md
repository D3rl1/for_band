# 秋日树框：选定素材与接入参考

用户已选的是 `autumn-wrap`：枝条展开版，树高于头像，包裹头像左半边；不是树冠收窄向上的版本，也不是头像与树等高的版本。

当前交付是素材、独立渲染参考与预览，不包含生产佩戴 / 保存接口；内部 id `band-autumn-wrap` 与“秋日树框”均为工作标识，正式名称与描述未定，不把它们发布为定稿文案。

## 文件

- `assets/tree.webp`：静态树与地面，已经分离悬空叶子。运行时不必逐像素分割。
- `assets/leaf-1.webp` 至 `leaf-5.webp`：五张透明叶片，复用到六条循环轨迹，数量 / 尺寸 / 速度沿用用户认可的完整动效。
- `assets/still.webp`：减少动态效果时用的完整静帧。
- `assets/source-original.png`：用户选定构图的原始生成素材；不用于运行时加载。
- `assets/preview-manifest.json`：素材几何与文件信息，属于本样张，不是生产头像 / 头像框清单。
- `reference/autumn-wrap.js` / `reference/autumn-wrap.css`：无第三方依赖的可复用渲染参考。
- `../../../design-samples/autumn-avatar-frame/`：独立预览，不是新的产品页面。
- `SOURCE.md`：来源、选定版本与文件哈希。

## 在本地预览

在仓库根目录运行 `python -m http.server 8091 --bind 127.0.0.1`，打开 `http://localhost:8091/design-samples/autumn-avatar-frame/`。预览只读本目录的资源，没有接口、真实成员资料或写库行为。

## 与 for_band 的接点

当前头像统一由 `src/main/resources/static/band-media.js:86` 的 `BAND.paintAvatar(el, member)` 绘制。该函数会清空传入容器；头像框必须作为它的兄弟层，不能放在会被清空的头像内容里。`profile.html:79` 是本人名片的调用点；`band-profile.js:80` 是个人栏调用点，侧栏 / 手机标题栏 / 欢迎过渡也有共用调用，不能直接全局铺到所有尺寸。

参考结构（下面只是接入示例，不代表生产已变更）：

```html
<div class="band-autumn-wrap" id="frame-root">
  <div class="band-autumn-wrap__avatar" id="avatar-content"></div>
</div>
```

```js
BAND.paintAvatar(document.getElementById('avatar-content'), member);
const frame = BandAutumnFrame.mount(document.getElementById('frame-root'), {
  assetBase: '/vendor/avatar-frames/autumn-wrap/assets/'
});
frame.ready.catch(() => { /* 仅装饰降级；原头像保持 */ });
// 更换框、重绘名片或销毁宿主时先 frame.destroy()。
```

通过 `<head>` 显式加载 CSS / JS；正式接入时将样张 CSS 的作用域合入项目样式入口，不动态注入样式。只裁切头像内容，外层与祖先需给装饰留出空间；保留原 FLIP 落点与头像首字回退，不能整块替换既有头像锚点。

## 已选几何与动画

原图正方形坐标中，头像左上是 `(19%, 18%)`，直径为图宽的 `78%`。以头像直径 D 为基准，树层边长 `1.28205128 D`，偏移 `(-0.24358974 D, -0.23076923 D)`。只裁切头像；树层自然覆盖头像左侧 / 左上与底部。这里没有写字、印章、边框或光圈。

树与地面固定；六条叶片路径各自缓慢下落、摆动、翻转，接近地面淡出，保留原完整动效。渲染器缓存图片，暂停 / 离屏 / 页面隐藏时停止绘制；`prefers-reduced-motion` 用静帧，运行中切换会生效。装饰不拦点击，canvas 为 `aria-hidden`。不往真实头像中央额外添加占位文字。

`BandAutumnFrame.mount(root, options)` 幂等，返回 `ready`、`setPaused(boolean)`、`destroy()`；资源失败会移除装饰，保留头像；`staticOnly: true` 可用于经确认只展示静帧的场景。小头像适配与展示范围还需生产规格确认。

## 从样张到正式佩戴还需做的事

1. 保持 #260 的入口工作边界；头像框正式机制另开用户分配的编号。
2. 拍板佩戴展示范围、可用对象、正式名称与描述。暂不假定全员发放或纪念资格。
3. 设计独立的受控头像框 id 与清单、本人选择 / 取消 / 保存、后端合法 id / 权限校验及持久化。不能把框 id 混入 `member.avatar`，也不能用 localStorage 冒充服务器保存。涉及表 / 权限时先获批准，并同步 docs/13 / docs/02 与反例测试。
4. 正式定稿后把运行资源放进 `src/main/resources/static/vendor/avatar-frames/autumn-wrap/`，接到本人名片等获准位置；未知框回退无框，头像与框各自独立。不把整个演示 HTML 嵌进业务页面。
5. 在装扮的头像框栏目接选择、实时预览、取消还原和只提交变化；刷新 / 重新登录 / 别人查看都按服务器数据回显。
6. 按仓库流程执行前后端验收、Maven、必要的 restart、refresh `[3/5]` 下发一致性及 spec_check，填写四样回执后才算上线。

## 验收重点

- 选定素材与几何正确，树高于头像、包裹左侧，完整落叶动效没有被减少。
- 桌面 / 390 / 320px 与现有日夜主题不溢出、不挡姓名 / 编辑按钮，装饰不拦头像点击。
- 替换头像后框不消失；切框 / 不用框 / 取消 / 保存失败 / 重登 / 看他人均正确，框不是头像替代图。
- reduce、运行中切换、暂停 / 恢复 / 隐藏页 / 销毁 / 重挂载，以及素材失败均有证据。
- 若改到权限 / 结构，补越权、未知框 id、只读身份等反例；既有头像与资料保存不能回归。

灰色圆形、标题和暂停按钮都是预览控件，不是正式头像或产品新增内容。
