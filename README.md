# GPS 定位器

一个面向 Android 开发和 QA 测试的模拟位置产品。支持单点、全球地区检索、多点路线、历史、文件交换、加密同步和 ADB 多设备自动化。

## 功能

- 清晰的 OpenStreetMap 地图拖动、缩放和点击选点
- 覆盖 200 多个国家和地区，支持国家下拉、城市与地区逐级联想及组合检索
- 跟随系统、简体中文、English、日本語和 Español 应用语言切换
- 当前地区或任意坐标收藏，Pro 不限制收藏数量
- 收藏位置预览、删除和一键开始模拟
- 收藏分组、搜索和免费版 5 个收藏上限
- 多点路线、速度、途经点停留和循环模拟
- GPX/KML 导入导出与米级摇杆微调
- 团队模板可勾选需要共享的收藏位置和路线，导入前预览覆盖数量，不携带个人历史或同步密钥
- 位置历史和一键恢复
- Google Play 一次性 Pro 购买与恢复购买
- 端到端加密云备份、多设备冲突重试和删除标记合并
- 可在应用内清除本地位置、路线、历史和企业报告，并保留付费权益
- 企业套件可同步切换 GPS 与 IANA 时区，结束后恢复每台设备原时区
- 本机企业控制台可向任意设备子集快速下发坐标/时区，停止后自动生成并下载 JSON/JUnit 报告
- 前台持续模拟 GPS/网络位置，通知栏可随时停止

Debug 构建自动启用 Pro，便于本地验收。Release 构建默认为 Free，只有 Google Play 已确认购买后才激活 Pro；ADB 企业控制还需要有效的签名许可证。

## 环境要求

- Android Studio Ladybug 或更新版本
- JDK 17（Android Studio 自带的 JBR 即可）
- Android SDK 35
- Android 8.0（API 26）或更高版本的真机/模拟器

## 构建

```bash
./gradlew assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 真机使用

1. 在系统设置中开启开发者选项。
2. 打开“开发者选项”中的“选择模拟位置信息应用”。
3. 选择“GPS 定位器”。
4. 返回 App，拖动/点击地图，或通过顶部的地区按钮选择位置。
5. 点击“开始模拟”，允许位置与通知权限。
6. 使用 App 内的“停止”按钮或通知栏动作结束模拟。

如果“选择模拟位置信息应用”列表中没有出现本 App，请确认安装的是 `2.0.0` 或更新版本；安装更新后退出并重新进入开发者选项。部分系统还需要先启动一次 App，列表才会刷新。

部分银行、支付、出行和游戏 App 会主动识别并拒绝模拟位置，这是目标 App 的安全策略，本项目不会尝试绕过。请仅在自己有权测试的设备和应用中使用。

## 地图与坐标

地图和地区检索数据来自 OpenStreetMap，地区检索需要联网。地图坐标和 Android Location API 均使用 WGS84。中国大陆部分商业地图使用 GCJ-02，因此在这些地图中查看时可能出现数百米偏移。

国家列表来自系统 ISO 国家数据，覆盖 200 多个国家和地区。国家名称、界面文本以及 OpenStreetMap 搜索结果会跟随 App 当前语言。

## 商业服务配置

Debug 默认使用公共 OpenStreetMap/Nominatim。商业发布时应通过 CI 环境变量切换到自建或商业服务：

```bash
GPS_TILE_URL_TEMPLATE='https://tiles.example.com/{z}/{x}/{y}.png' \
GPS_GEOCODING_BASE_URL='https://geocode.example.com' \
GPS_MAP_ATTRIBUTION='© Your map provider' \
GPS_PRIVACY_POLICY_URL='https://example.com/privacy/gps-locator' \
GPS_SUPPORT_EMAIL='support@example.com' \
GPS_ENTERPRISE_SALES_EMAIL='enterprise@example.com' \
GPS_ENTERPRISE_LICENSE_PUBLIC_KEY='<Base64 DER RSA public key>' \
GPS_COMMERCIAL_RELEASE=true \
./gradlew bundleRelease
```

以上 `example.com` 值仅用于说明，商业构建会拒绝示例、测试和本机地址，也会拒绝占位邮箱与不可公开访问的隐私政策 URL。`GPS_PRIVACY_POLICY_URL` 会写入 App 的“设置 > 隐私与数据”，`GPS_SUPPORT_EMAIL` 会写入“设置 > 关于与支持”，`GPS_ENTERPRISE_SALES_EMAIL` 会写入 Pro 页面中的企业咨询入口；未单独设置销售邮箱时会回退到客服邮箱。`GPS_ENTERPRISE_LICENSE_PUBLIC_KEY` 必须是至少 2048 位 RSA 公钥的 Base64 DER 编码，只放公钥，私钥不得进入仓库或构建环境。商业构建要求这些配置有效。Google Play 上传产物位于 `app/build/outputs/bundle/release/app-release.aab`。本地默认产物未使用生产上传密钥签名，发布前必须配置 Play App Signing 与私有上传密钥。

## 配套服务

- [`cloud-server`](cloud-server/README.md)：无第三方运行依赖的加密同步服务，附带 Caddy HTTPS、持久卷和防误覆盖备份恢复的私有部署包。
- [`desktop-cli`](desktop-cli/README.md)：本机 Web 控制台、多设备 ADB 控制、版本化团队场景套件、企业许可证，以及 JSON/JUnit 汇总报告。
- [`store-listing`](store-listing/README.md)：四语言 Google Play 文案、真实设备截图、特性图与自动校验工具。
- [`docs/COMMERCIALIZATION.md`](docs/COMMERCIALIZATION.md)：Free/Pro/Enterprise 产品与定价设计。
- [`docs/RELEASE.md`](docs/RELEASE.md)：Google Play 商品、签名、地图服务和发布检查清单。
- [`docs/PRIVACY_POLICY.md`](docs/PRIVACY_POLICY.md)：发布前需要补全并托管的隐私政策模板。
