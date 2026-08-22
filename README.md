# HisEpdCalendar — 海信 A2 Pro 墨水屏桌面日历

把一台闲置的**海信 A2 Pro**（双屏手机：5.5" OLED 前屏 + 5.2" 1080×1920 墨水屏后屏）
改造成横版墨水屏桌面日历时钟。

**核心思路：显示管线全部复用原厂**——原厂锁屏引擎每分钟闹钟唤醒 → 读壁纸文件 →
上屏 → 回 suspend，自研部分只负责"画什么"。锁屏状态下唯一功耗就是原厂引擎的
分钟级局部刷新，壁纸内容按计划（默认每 3 小时）经 WiFi 拉取云端数据本地渲染，
联网窗口仅约 10 秒。

## 功能一览

- 横版日历壁纸（月历 + 农历/节气/黄历/节假日休班 + 天气实况预报 + 一言）
- **实时时钟 / 实时电池**（含充电闪电）：由 Xposed hook 每分钟随引擎现场叠绘，
  壁纸几小时一换、时间却分钟级准，零额外唤醒
- 去除原厂锁屏迷你钟 / 解锁条 / 电池角标（布局铺满）
- 控制台 App：服务器/密钥/刷新正则（对 `HH:mm` 逐分钟匹配，如每 3 小时整点）、
  立即刷新、解析测试
- 软开关：关闭「桌面日历模式」一分钟内还原原厂界面（免重启）；「刷白墨水屏」
  供长期收纳
- **单 APK 双角色**：控制台 App 与 Xposed 模块合为一体，一次构建一次部署

## 系统架构

```
┌─ 云端服务端（Flask，不在本仓库；数据契约见 docs/api-data-sample.json）──┐
│ GET /api/data (X-API-Key)：system_info / weather / hitokoto / calendar   │
└──────────────┬──────────────────────────────────────────────┘
               │ WiFi（仅刷新期间开启，约 10s）
┌──────────────▼ 手机端 com.hi.epdcalendar（本仓库 epdcalendar-app/）────┐
│ RefreshService：自愈引擎设置 → 开WiFi → GET /api/data                  │
│   → RenderActivity 内 WebView 渲染 960×540 CSS @2x → 1920×1080        │
│   → 顺时针转90° → 1080×1920 PNG → su 原子落位 → 关WiFi → 布防下一轮   │
│ ScheduleLogic：正则计划器 + AlarmManager 精确闹钟                      │
└──────────────┬──────────────────────────────────────────────┘
               │ 文件级交接：/sdcard/eink_clock/eink_lockscreen_wallpaper.png
┌──────────────▼ 原厂锁屏引擎 + 本仓库 Xposed hook（同 APK 内）──────────┐
│ EInkLockScreenEngine（原厂，分钟闹钟循环）                              │
│ hook：drawWallpaper → 画我们的壁纸 + 实时时钟/电池                      │
│       showMiniClock → no-op；getBottomBar → 原图直返                   │
└──────────────────────────────────────────────────────────────────────┘
```

## 目录结构

| 路径 | 内容 |
|---|---|
| `epdcalendar-app/` | 合并版单 APK 源码：控制台 App（`src/com/hi/epdcalendar/`）+ Xposed hook（`src/com/hi/epdclock/`）+ Xposed API 编译桩（`xposed-stubs/`，不进 dex）+ 渲染模板与 38 个天气 SVG 图标（`assets/template/`） |
| `tools/build.py` | 构建/部署：`--install` 装机补权限，`--deploy` 加 modules.list 同步+重启验证 |
| `tools/render_preview.py` | PC 端模板预览（Playwright，配合 docs/api-data-sample.json 离线开发） |
| `tools/patch_installer_v4.py` | Xposed 安装器二进制补丁脚本（配合补丁版安装器使用，见 docs/补丁版安装器说明.md） |
| `docs/社区部署与卸载指南.md` | **普通用户安装/卸载步骤（小白可跟做）** |
| `docs/开发指南.md` | 构建/模板/hook/服务端契约的开发说明 |
| `docs/api-data-sample.json` | /api/data 响应样例（无服务端也能开发模板） |

所需二进制制品（Xposed 框架 zip、补丁版安装器 APK、已构建的模块 APK 等）
见本仓库 **Releases**。

## 快速开始

见 **[docs/社区部署与卸载指南.md](docs/社区部署与卸载指南.md)**。
前提：A2 Pro 已解锁 BL 并安装 Magisk。全程手机操作，无需电脑（仅个别可选项）。

## 已知事项

- **机型限定**：hook 目标（`com.hmct.einklockscreen.EInkLockScreenEngine`、
  `BottomBarController`）是海信 ROM 独有类，其他机型安装无效但完全无害
  （找不到类仅记一条 Xposed 日志）
- Xposed 安装器使用补丁版（仅改一处状态检测路径，与官方原版的差异见
  [docs/补丁版安装器说明.md](docs/补丁版安装器说明.md)）；其「下载」页指向的
  官方服务器已于 2021 年关站，主页的 ZIP 加载报错可无视
- 服务端代码不在本仓库：按 `docs/api-data-sample.json` 的契约自行实现即可
  （任意 HTTP 服务，X-API-Key 鉴权，返回同样 JSON 结构）

## 致谢

- [Xposed](https://github.com/rovo89/XposedInstaller)（rovo89）与
  [topjohnwu](https://github.com/Magisk-Modules-Repo/xposed) 的 systemless 打包
- [lunar_python](https://github.com/6tail/lunar-python)（农历/节气/干支/黄历）
- 高德天气 API、[一言 hitokoto](https://v1.hitokoto.cn/)
- 本项目由个人自用项目精简开源

## 许可

[MIT](LICENSE)
