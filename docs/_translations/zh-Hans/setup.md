---
title: 安装并启动 Porter
lang: zh-Hans
translation_key: setup
language_name: 简体中文
description: 从 GitHub Releases 下载 Porter，然后按照以下步骤操作。
---
# 安装并启动 Porter
{: #install-and-start-porter }

从 [GitHub Releases](https://github.com/d4rken-org/porter/releases) 下载 Porter，然后按照以下步骤操作。

## 安装
{: #install }

1. 从发布版本的 **Assets** 区域下载 Porter APK。包含源代码的 ZIP 和 TAR 文件不是 Android 应用。
2. 在设备上打开 APK。如果 Android 提示，请允许浏览器或文件管理器安装来自此来源的应用。
3. 打开 Porter。

直接支持 Porter 的应用只需安装 Porter。若某个应用仅支持 Shizuku，还需安装同一发布版本中的 **Porter Compatibility**。请先卸载 Shizuku，因为兼容应用无法与它同时安装。使用这些应用期间，请保留兼容应用。详情见[兼容性指南](/compatibility)。

## 选择启动方式
{: #choose-how-to-start }

| 你的设备 | 启动方式 |
| --- | --- |
| 支持无线调试的 Android 11 或更高版本 | [无线调试](#wireless-debugging) |
| Android 7.0 或更高版本，且有电脑 | [USB 调试](#with-a-computer) |
| 已取得 root 权限 | [Root](#root) |

## 无线调试
{: #wireless-debugging }

需要 Wi-Fi 连接以及 Android 11 或更高版本。部分厂商限制无线调试；若此功能不可用，请使用电脑。

1. 在 Android 设置中启用**开发者选项**。通常需要打开**关于手机**，连续点击**版本号**七次。具体位置因设备而异。
2. 在开发者选项中启用 **USB 调试**和**无线调试**。如果出现网络授权提示，请确认。
3. 在 Porter 的无线调试启动区域点击**配对**。允许通知，以及系统要求的附近设备或本地网络访问权限。
4. 打开 Android 的**无线调试**设置，点击**使用配对码配对设备**，并保持该对话框打开。
5. 展开 Porter 的配对通知，输入 Android 显示的配对码。等待配对成功。
6. 返回 Porter，在无线调试区域点击**启动**。
7. 确认 Porter 显示服务正在运行。

Porter 停止时不会关闭 Android 的调试设置。不再需要调试访问时，可在开发者选项中关闭。

通常只需配对一次。启动服务是独立的步骤，设备重启后需要重新启动服务。如果 Android 忘记了配对信息，请再次执行上述步骤。

如果你在**设置**、**启动**、**配对方式**中选择了应用内对话框，请等待 Porter 对话框发现配对服务，然后在其中输入配对码。如果要求输入端口，请使用 Android 配对码对话框中的配对端口，不要使用无线调试主界面上的连接端口。

## 使用电脑
{: #with-a-computer }

1. 在电脑上安装 Google 的 [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)。
2. 在 Android 设备上启用开发者选项和 **USB 调试**。
3. 使用支持数据传输的 USB 线连接设备。解锁设备并批准调试连接。仅授权你信任的电脑。
4. 在 Platform-Tools 文件夹中打开终端，运行 `adb devices`。Windows PowerShell 请使用 `./adb.exe devices`；macOS 或 Linux 中，如果 ADB 不在 PATH 中，请使用 `./adb devices`。
5. 在 Porter 中找到通过电脑启动的区域，点击**查看命令**。在电脑上原样运行该命令；如有需要，按上一步说明调整 `adb` 可执行程序的前缀。
6. 确认 Porter 正在运行。之后可以拔掉 USB 线。

如果连接了多个设备，请在显示的命令中紧接 `adb` 后插入 `-s DEVICE_SERIAL`。序列号应使用 `adb devices` 列出的、运行 Porter 的那台设备的序列号。

更新或重新安装 Porter 后，请重新获取启动命令，因为路径可能改变。不要使用从 Shizuku 或其他安装中复制的启动命令。

## Root
{: #root }

此方式适用于已经具备可用 root 权限的设备。安装 Porter 不会为设备获取 root。

1. 打开 Porter，在 root 启动区域点击**启动**。
2. 在 root 管理器中批准 Porter 的请求。
3. 确认 Porter 正在运行，且启动模式显示为 root。

在 root 与调试访问之间切换前，请先停止当前 Porter 服务。

## 授权应用
{: #allow-an-app }

打开你想使用的应用，启用其中的 Porter 或 Shizuku 集成，然后批准 Porter 的访问请求。仅批准你信任的应用：它们可以利用 Porter 的调试或 root 权限执行操作。

要撤销访问权限，请打开 Porter 的**应用**页面，关闭对应应用的授权。Porter 与 Shizuku 分别保存授权。

要暂停所有应用的访问，请关闭此页面顶部的**允许应用访问**。各应用的授权设置会保留。重新打开该开关即可恢复访问。已经启动的 shell 命令可能在暂停期间继续运行。

如果应用提供服务选择器，请选择 Porter，并遵循该应用自己的设置说明。如果切换后需要重启应用，请在 Android 应用设置中**强行停止**它，然后重新打开。

## 停止 Porter
{: #stop-porter }

点击主页上显示 Porter 正在运行的卡片，再选择**停止 Porter**。已连接的应用将失去访问权限，直到你再次启动 Porter。

## 更新 Porter
{: #update-porter }

将新版 APK 覆盖安装到现有应用上，然后重新启动 Porter。如果使用 Porter Compatibility，也请更新到同一发布版本。Android 要求更新包的签名匹配；若无法安装，请查看[安装问题](/troubleshooting#android-wont-install-an-apk)。
