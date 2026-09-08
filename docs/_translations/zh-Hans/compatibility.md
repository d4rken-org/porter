---
title: 应用兼容性
lang: zh-Hans
translation_key: compatibility
language_name: 简体中文
description: Porter 支持兼容应用使用的 Shizuku API。是否需要可选的兼容应用，取决于应用的连接方式。
---
# 应用兼容性
{: #app-compatibility }

Porter 支持兼容应用使用的 Shizuku API。是否需要可选的兼容应用，取决于应用的连接方式。

| 应用支持的服务 | 需要安装 |
| --- | --- |
| 直接支持 Porter | Porter |
| 仅支持 Shizuku | Porter 和 Porter Compatibility；先卸载 Shizuku |
| 同时支持两者，并可选择服务 | Porter，然后在应用内选择 Porter |

兼容应用帮助现有 Shizuku 应用找到 Porter。启动服务、显示权限请求和管理授权仍由 Porter 负责。使用需要兼容应用的客户端时，请保持兼容应用已安装。

## 从 Shizuku 切换
{: #switch-from-shizuku }

对于直接支持 Porter 的应用，可以保留 Shizuku。启动 Porter，在应用中选择它，并批准新的请求。如果应用要求重启，请在 Android 设置中强行停止应用，再重新打开。

对于仅支持 Shizuku 的应用：

1. 停止 Shizuku 并卸载其管理应用。使用 Shizuku 的其他应用可以保留。
2. 安装同一发布版本中的 Porter 与 Porter Compatibility APK。
3. 启动 Porter。
4. 在 Android 设置中强行停止客户端应用，重新打开，并启用其 Shizuku 集成。
5. 批准 Porter 显示的访问请求。

之前的 Shizuku 授权不会迁移。你需要重新选择哪些应用可以使用 Porter。

## Porter 和 Shizuku 可以同时运行吗？
{: #can-porter-and-shizuku-run-together }

可以。两者可以同时安装并运行。支持选择服务的应用一次只连接一个服务。

**Porter Compatibility 无法与 Shizuku 同时安装。** 它使用 Shizuku 的 Android 应用身份来兼容旧应用。Android 将它们视为同一应用的不同安装版本，而非两个独立应用。使用相同应用身份的分支也受此限制。

要切回 Shizuku，请卸载 Porter Compatibility 并重新安装 Shizuku。重启客户端应用，并在 Shizuku 中批准访问。你可以保留独立的 Porter 应用。

## 应用仍然要求 Shizuku
{: #an-app-still-asks-for-shizuku }

即使访问权限由 Porter 提供，旧应用的设置中也可能仍显示 Shizuku。这是正常现象。

兼容应用覆盖了常见的 Shizuku 发现方式。依赖特定 Shizuku 页面、内部组件或非常旧的 API 的应用可能需要更新。如果某个应用无法连接，请在 [Porter 问题报告](https://github.com/d4rken-org/porter/issues)中注明应用名称和版本。
