# PhiSlow

独立运行的 Android 音游练习工具，提供慢放、触屏判定、Autoplay 和段落练习。程序参考 Phigros 的交互及 Phira 的谱面与判定语义，使用独立实现和自制按键资源。

**公开版不包含任何内置谱面、音乐或曲绘。** 请自行导入合法拥有的练习素材；程序无需安装 Phigros 或 Phira。源代码：[cxabc4/PhiSlow](https://github.com/cxabc4/PhiSlow)。

## 导入与练习

支持 Android 8.0 及以上。安装 `PhiSlow-Public.apk` 后，在选曲页点击导入：

- **ZIP / PEZ 谱面包**：包含谱面 JSON 和配套音乐，可选曲绘；支持 Phira 常用的 `info.yml` 单行元信息字段与文件引用，也可使用 `info.txt`。包内有多个同类文件时，需在元信息中指定文件。
- **JSON＋音乐**：依次选择谱面 JSON 和配套音频。

当前谱面解析支持 **PGR JSON V1/V3**，包括四类音符、判定线移动／旋转／透明度／速度事件，以及 `blockAreaList` 噪域。**尚不支持 RPE JSON 和 PEC**；ZIP 是打包方式，不代表其内部所有谱面格式均受支持。

导入完成后从曲目列表选择歌曲并开始练习。文件保存到应用内部存储，重启或覆盖更新后保留；卸载或清除应用数据会删除导入内容、设置和收藏。

相同谱面和音乐重复导入时复用已有曲目及其元信息。`info.yml` 的 `offset`（秒）与 JSON 内偏移量相加；可选曲绘会生成独立的模糊背景。单个音频上限 256 MiB，整个解压包上限 512 MiB / 256 个条目，谱面 JSON 上限 16 MiB。

## 功能

- 音频变速与谱面时间轴同步，尽量保持音调；设备不支持的速度会提示。
- 点按、滑动、长按、甩动、多点触控与 Autoplay 开关。
- 暂停、重试、进度定位、练习起点／终点与区间收藏；收藏段落可以自定义名称，暂停页左侧点击快速载入。
- 曲目收藏、搜索、难度选择与横屏操作。
- 判定窗口、按键宽度、纯色／模糊曲绘背景、音频延迟与噪域开关。
- 谱面帧率上限：60 / 90 / 120 / 144 / 无上限；默认无上限，跟随屏幕同步。
- 实验性流速设置：默认随倍速降低，也可在慢放时保持 1× 的音符接近流速。
- 自制按键美术与 ZIP 资源包导入、预览、应用、删除。

练习判定使用独立实现，未承诺与原游戏全部细节一致。噪域使用自制 Java 噪声与触点效果，边缘外观与原版材质有区别，尚未实现触摸音乐低通。低倍速音质受 Android 设备实现影响。本应用没有联网权限。

## 本地构建

不依赖 Gradle、AndroidX 或第三方 Android 库。需要 JDK 17，以及 Android SDK 中的 `platforms/android-36.1` 和 `build-tools/36.1.0`。

```powershell
.\build.ps1 -JavaHome 'C:\path\to\jdk-17' -SdkRoot 'C:\path\to\Android\Sdk'
```

默认构建公开版，输出根目录的 `PhiSlow-Public.apk`。只暂存原创按键资源和许可文本，即使本地存在私人曲库，也不会将其装入公开 APK。

```powershell
.\build.ps1 -Variant Public -WithTests
python tools/package_source.py
```

测试 APK 位于 `build/PhiSlow-tests.apk`，源码包为 `PhiSlow-Source-<版本号>.zip`（当前为 `PhiSlow-Source-0.5.0.zip`）。打包工具从应用清单读取版本号。源码包采用允许清单，仅包含程序、原创按键源文件、测试源码、构建工具与许可，不包含谱面、音乐、曲绘、私人曲库清单、提取脚本、逆向文档、签名密钥或用户数据。

构建脚本保留三种独立产物：`Public` 生成 `PhiSlow-Public.apk`；`Full` 生成 `PhiSlow-Practice-Full.apk`；`Update` 生成 `PhiSlow-Practice-Update.apk`。`Both` 只生成 Practice 完整包和增量包，不覆盖 Public 包。

Practice 包供已有私人曲库的本地使用者显式构建，完整包先保存曲库，增量包覆盖更新并读取已保存曲库。所需的私人素材和曲库清单不随公开仓库提供，两份 Practice APK 不发布到 GitHub。三个产物保留相同包名 `com.phislow.app` 以兼容覆盖更新。

调试签名保存在 `build/debug.keystore`。覆盖安装必须使用相同密钥；保留该文件可持续构建自己的更新包，其他人重新构建会生成自己的密钥。

## 验证

```powershell
adb install -r .\PhiSlow-Public.apk
adb install -r .\build\PhiSlow-tests.apk
adb shell am instrument -w com.phislow.tests/com.phislow.tests.ChartImportInstrumentation
```

部分历史仪器测试使用私人曲库样本，仅适合相应本地环境；公开版本不携带这些样本。测试代码可以在运行时构造简单谱面、音频和 ZIP，验证导入及播放行为。

## 许可与参考项目

程序采用 **GPL-3.0-only**，完整条款见 [LICENSE](LICENSE)。参考项目、作者、用途、固定提交与历史本地调查工具逐项见 [NOTICE](NOTICE)。应用内「设置」→「关于与开源许可」提供同一声明与完整许可文本。

- [Phira Pro](https://github.com/Phira-Pro/Phira-Pro/tree/5c28e71955d4ee74fd81f220efdccbdec4d727ed)：噪域区域、触点屏蔽与图层语义。
- [Phira](https://github.com/TeamFlos/phira/tree/ac4795fff2efd4cb2c7ef212bbbf74bdb4c42ae3)：谱面解析、判定线、音符生命周期与判定语义。
- [Phira Documents](https://github.com/TeamFlos/phira-docs)：资源包格式参考，文档使用 CC-BY-4.0。
- 用户提供的 Phiround.zip：按键设计与资源格式参考，未转载包内图片或音效；本项目按键和 SVG 打击特效为自制资源。

Phigros、相关商标及音乐、谱面、曲绘的权利属于各自权利人。程序的 GPL 许可不授予这些素材的再分发权，用户导入内容需遵守其各自授权。发布修改版 APK 时请提供对应源码及完整许可和归属声明。
