---
title: Porter
lang: zh-Hans
translation_key: index
language_name: 简体中文
description: Porter 是 Shizuku 的精简分支，持续维护，通过 Shizuku API 为 Android 应用提供 ADB 访问权限，也可选用 root。
---
# 为你的应用提供 ADB 访问权限
{: #adb-access-for-your-apps }

Porter 是 [Shizuku](https://github.com/thedjchi/Shizuku) 的精简分支，持续维护，通过 Shizuku API 为 Android 应用提供 ADB 访问权限，也可选用 root。

由你决定哪些应用可以使用它。

## 用户指南
{: #for-users }

1. 从 [GitHub Releases](https://github.com/d4rken-org/porter/releases) 下载 Porter。
2. 按照[安装与启动指南](/setup)进行设置。
3. 打开支持的应用，批准其 Porter 访问请求。

需要 Android 7.0 或更高版本。在 Android 11 及更高版本上，可通过无线调试启动 Porter，无需电脑。较旧的设备需要电脑或 root。

更多帮助：[应用兼容性](/compatibility)和[故障排除](/troubleshooting)。

## 已经在使用 Shizuku？
{: #already-using-shizuku }

Porter 拥有独立的应用身份，可与 Shizuku 同时运行。仅识别 Shizuku 的应用需要可选的 Porter Compatibility 兼容应用，它会替代已安装的 Shizuku 应用。

[为你的应用选择合适的配置](/compatibility)。

## 为什么创建 Porter
{: #why-porter-exists }

我在自己的应用 [SD Maid SE](https://github.com/d4rken-org/sdmaid-se) 和 [Butler](https://github.com/d4rken-org/butler) 中使用 Shizuku。由于原版 Shizuku 已不再积极维护，我创建了 Porter，为需要 ADB 访问权限的应用提供精简、稳定且持续维护的替代方案。

## 开发者指南
{: #for-developers }

欢迎其他开发者直接支持 Porter 权限。[为你的应用添加原生 Porter 支持](/developers)。

## 需要帮助？
{: #need-help }

请先查看[故障排除](/troubleshooting)，或[报告问题](https://github.com/d4rken-org/porter/issues)。

Porter 是 Shizuku 的独立延续，基于 [RikkaApps](https://github.com/RikkaApps/Shizuku)、[thedjchi](https://github.com/thedjchi/Shizuku) 及其贡献者的工作。
