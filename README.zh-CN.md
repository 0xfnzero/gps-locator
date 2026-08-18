# GPS 定位器

开源 Android **模拟位置** / **假 GPS** 工具，面向开发和 QA。支持单点选点、全球地区检索、GPX/KML 路线回放、加密备份，以及 ADB 多设备控制。

全部功能免费开放，没有付费解锁或商业套餐。

[English README](README.md) · [发布页](https://github.com/0xfnzero/gps-locator/releases)

## 功能

- OpenStreetMap 地图拖动、缩放和点击选点
- 覆盖 200 多个国家和地区，支持国家、城市与地区检索
- 简体中文、English、日本語、Español 和跟随系统语言
- 收藏、分组、搜索、历史和米级摇杆微调
- 多点路线、速度、途经点停留和循环模拟
- GPX/KML 导入导出与团队模板交换
- 可选的端到端加密云备份
- 本机 Web 控制台、ADB 多设备控制和 JSON/JUnit 报告

部分银行、支付、出行和游戏 App 会拒绝模拟位置，这是目标 App 的安全策略。请只在自己有权测试的设备和应用中使用。

## 安装

从 [GitHub Releases](https://github.com/0xfnzero/gps-locator/releases) 下载 APK。

1. 打开开发者选项。
2. 在“选择模拟位置信息应用”中选择“GPS 定位器”。
3. 允许位置和通知权限后开始模拟。

## 从源码构建

需要 Android Studio Ladybug 或更新版本、JDK 17、Android SDK 35。

```bash
./gradlew assembleRelease
```

APK 位于 `app/build/outputs/apk/release/app-release.apk`。

## 许可证

[MIT](LICENSE)
