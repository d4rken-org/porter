---
title: 故障排除
lang: zh-Hans
translation_key: troubleshooting
language_name: 简体中文
description: 使用调试访问时，设备重启后需要重新启动 Porter，这是正常现象。打开 Porter，使用你的启动方式。无线调试配对本身不会启动服务。
---
# 故障排除
{: #troubleshooting }

## Porter 未运行
{: #porter-is-not-running }

使用调试访问时，设备重启后需要重新启动 Porter，这是正常现象。打开 Porter，使用你的[启动方式](/setup)。无线调试配对本身不会启动服务。

## 自动启动不起作用
{: #automatic-start-does-not-work }

安装后请先手动启动 Porter 一次，再使用**开机启动**。成功通过调试启动后，系统会授予后续自动启动所需的 Android 设置权限。如果通知提到 `WRITE_SECURE_SETTINGS`，请使用电脑再次启动 Porter，然后重试。

自动启动仍取决于 Android 是否提供调试访问，以及是否允许 Porter 在后台运行。失败时请手动启动。

## 无线配对无法完成
{: #wireless-pairing-does-not-finish }

- 保持设备连接 Wi-Fi，并确认无线调试已启用。
- 允许 Porter 发送通知，以便输入配对码。如果 Android 要求，也请允许附近设备或本地网络访问。
- 在 Porter 通知中输入配对码时，保持 Android 配对码对话框打开。若配对码过期，请重新打开配对对话框。
- 在 Porter 应用内对话框中输入时，请复制配对码对话框中的配对端口，而不是无线调试主界面的连接端口。
- 如果 VPN 或本地网络限制阻止设备发现，请尝试允许设备互相通信的网络。

如果配对码被拒绝，请在通知中选择**重试**。如果通知消失或仍显示旧的配对码，请返回 Porter 的**配对**并选择**重新开始配对**。关闭 Android 的旧配对码对话框，打开新的对话框，然后输入新的配对码。无需清除 Porter 的缓存或应用数据。

在 Android TV 上，无论配对成功、失败还是超时，Porter 都会显示结果。失败后请选择**重试**；如有提示，请重新启用配对无障碍服务。如果 Porter 没有自动打开，请手动打开以查看已保存的结果。配对成功后仍需单独执行**启动**这一步。

新的配对会在 Android 的已配对设备列表中显示为 **Porter**。已有条目可能仍显示为 **shizuku**，直到你删除该条目并重新配对。更改显示名称无需替换已保存的密钥，现有配对可以继续使用。该名称与 Porter 应用列表中的 Shizuku API 标记无关。

如果无线调试不可用或不稳定，请[使用电脑启动](/setup#with-a-computer)。

## 电脑无法找到设备
{: #the-computer-cannot-find-the-device }

运行 `adb devices`。如果显示 `unauthorized`，请解锁设备并批准调试请求。如果没有设备显示，请检查 USB 调试、尝试支持数据传输的 USB 线及其他 USB 接口，并确认电脑是否需要安装厂商 USB 驱动。

如果启动命令提示文件不存在，请从已安装的 Porter 中通过**查看命令**复制新的命令。

## Porter 反复停止
{: #porter-keeps-stopping }

先检查设备是否重启，或 Android 是否关闭了调试。必要时重新启动 Porter。

如果设备没有重启，请检查厂商针对 Porter 的电池和后台应用设置。如果后台运行受到限制，请允许它运行。网络变化和厂商对 Android 的修改都可能影响调试访问。

报告反复停止的问题时，请附上设备型号、Android 版本、启动方式，以及 Porter 停止前发生的事情。

## 应用无法连接
{: #an-app-cannot-connect }

1. 确认 Porter 显示服务正在运行。
2. 检查[应用是否需要 Porter Compatibility](/compatibility)。
3. 若应用提供服务选择器，请选择 Porter，在 Android 设置中强行停止应用后重新打开。
4. 在应用内启用集成，并批准 Porter 的请求。
5. 在 Porter 的**应用**页面检查该应用，并确保**允许应用访问**已开启。

如果使用兼容应用，两个 Porter APK 必须来自相同的发布来源，并使用匹配的签名证书。卸载兼容应用会使旧客户端无法使用 Porter。

## Android 无法安装 APK
{: #android-wont-install-an-apk }

如果你手动安装独立的 **Porter Compatibility** APK，请先卸载 Shizuku。即使 Android 识别到相同的应用身份，兼容应用也无法覆盖更新签名不同的 Shizuku。Porter 的集成替换会替你卸载 Shizuku。

对于 Porter 本身，旧开发版本的签名可能与公开发布版本不同。Android 无法将一个覆盖安装到另一个上。卸载旧版本也会删除应用数据，请先记录你的设置。然后从所需来源重新安装，并重新授权应用。

## 电视上的安装警告无法用遥控器选中
{: #a-tv-installation-warning-cannot-be-selected-with-the-remote }

Play 保护机制的警告及其**详细了解**或**仍然安装**按钮属于 Android 安装程序。Porter 无法改变这些按钮在遥控器下的焦点行为，而且仅凭该警告也无法说明 Google 标记某个 APK 的原因。

请确认 APK 来自 [Porter 发布页面](https://github.com/d4rken-org/porter/releases)。如果你在阅读警告后决定继续，使用 USB 或蓝牙鼠标或许可以选中这些按钮。如果你已有通往该电视的已授权 ADB 连接，也可以从那台电脑安装已下载的 APK：

```sh
adb -s TV_SERIAL install -r /path/to/porter-compat.apk
```

请将 `TV_SERIAL` 替换为 `adb devices` 中该电视对应的条目，并使用实际下载的 APK 路径。Android 仍可能阻止安装或要求确认。这并不能修复 Play 保护机制的按钮操作，也不保证系统允许安装。

如果安装仍被阻止，请报告警告的准确文字、电视型号、Android 版本和 Porter Compatibility 版本。直接支持 Porter 的应用不需要兼容 APK。

## 已授权，但操作仍失败
{: #access-is-allowed-but-an-operation-still-fails }

调试权限比 root 更有限。Android 版本和厂商还会施加额外限制，Porter 无法让所有应用操作都可用。也请检查客户端应用自身的要求。

在使用 MIUI 的小米/POCO 设备上，开发者选项可能有单独的 **USB 调试（安全设置）**开关。仅开启普通 USB 调试时，应用管理操作可能仍受限制。若需要这些操作，请开启额外设置并重新启动 Porter。名称和可用性因系统版本而异。

部分 OPPO/OnePlus 系统的开发者选项中有**权限监控**开关，会限制调试访问。关闭后可能允许相关操作，但会改变厂商的安全保护设置。这些厂商特定的解决方法来自[原版 Shizuku 指南](https://shizuku.rikka.app/guide/setup/)，尚未在实体设备上使用 Porter 验证。

## 运行时显示的版本不同
{: #the-version-shown-while-running-is-different }

**设置**中的**版本**条目显示 Porter 应用版本。点击服务卡片可打开**服务**界面，其中会显示已安装应用和运行中服务的版本名称、版本号和构建 ID，以及兼容的 Shizuku API 版本。若要分享诊断信息，请在**设置**、**帮助与支持**中[录制调试日志](#report-a-problem)。API 版本用于描述兼容性，并非 Porter 的发布版本号。构建不同时会显示**有可用的服务更新**。兼容的功能仍可使用，打开 Porter 也不会重启服务。请在 Android 主用户下于服务界面点击**更新服务**并确认，即可用运行中服务的权限应用已安装的构建，无需电脑，也无需重新建立无线调试连接。已连接的应用会短暂断开，授权会保留。如果替换失败，请在旧服务仍在运行时使用**重试更新**，或用你惯用的方式重新启动服务。

在**设置**中，**自动更新服务**可在 Porter 应用更新后检查是否有构建不同的服务正在运行。该选项默认关闭。Android 可能延迟后台任务，手动更新按钮始终可用。已停止的服务会保持停止。失败或中断的更新会显示在主屏幕上，启用看门狗时也是如此。

## 报告问题
{: #report-a-problem }

点击 Porter 的设置图标，打开**帮助与支持**。你可以通过电子邮件联系支持，访问 [Discord 社区](https://discord.gg/5hXXgwKNgm)，或[提交问题](https://github.com/d4rken-org/porter/issues)。

要附上调试日志，请选择**录制调试日志**，重现问题，再选择**停止录制**。在联系表单中选择已保存的调试日志，或从**已保存的调试日志**中分享。日志在分享前仅保存在设备上，可能包含应用名称、设备信息及通过 Porter 执行的操作。

请提供：

- Porter 版本，以及是否安装 Porter Compatibility。
- 设备型号与 Android 版本。
- 启动 Porter 的方式：无线调试、电脑或 root。
- 受影响应用及其版本。
- 你的操作、预期结果与实际结果。

提交截图和日志前请检查其中的个人信息。不要附上无线配对码或私钥。
