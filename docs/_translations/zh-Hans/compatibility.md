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
| 仅支持 Shizuku | Porter 和 Porter Compatibility；若已安装 Shizuku，Porter 会将其替换 |
| 同时支持两者，并可选择服务 | Porter，然后在应用内选择 Porter |

兼容应用帮助现有 Shizuku 应用找到 Porter。启动服务、显示权限请求和管理授权仍由 Porter 负责。使用需要兼容应用的客户端时，请保持兼容应用已安装。

## 从 Shizuku 切换
{: #switch-from-shizuku }

对于直接支持 Porter 的应用，可以保留 Shizuku。启动 Porter，在应用中选择它，并批准新的请求。已连接到 Shizuku 的应用在重启前会继续使用 Shizuku，因此请在 Android 设置中强行停止应用，再重新打开。

对于仅支持 Shizuku 的应用，FOSS 版本内置了匹配的 Porter Compatibility APK：

1. 安装并启动 Porter。在查看替换内容之前，请保留 Shizuku。
2. 从主屏幕或**设置**打开 **Shizuku 兼容性**。
3. 如果已安装 Shizuku，请选择**替换**，并确认**切换到 Porter**。Porter 会停止 Shizuku、替换其应用，并自动迁移符合条件的访问决定。如果 Porter 无法停止服务，它会提示你在 Shizuku 中停止服务后重试。
4. 否则请选择**自动安装**。
5. 返回客户端应用。如果没有导入已有的决定，请批准访问。如果仍然无法连接，请在 Android 设置中强行停止该应用，再重新打开。

Porter 会自动导入能够与已安装应用及其当前访问状态核对的决定。Porter 中已有的决定优先。Shizuku 的应用设置和配对信息不会导入。如果无法读取访问数据库，你可以继续并重新授权应用。Porter 会在移除 Shizuku 前保存符合条件的访问决定；如果安装失败，请重试，或使用**手动**，然后选择**导入已保存的授权**。

集成替换只能在 Android 主用户下使用。如果 Shizuku 安装在其他用户或工作资料中，请单独处理那一份安装。Porter 不会自动移除其他用户的应用。

兼容应用的 APK 仍可从同一发布版本单独下载。手动安装时，请停止并卸载 Shizuku，安装 Porter Compatibility，然后启动 Porter。要迁移符合条件的访问决定，请使用 Porter 的集成替换；仅打开替换对话框不会保存导入。设备的安装限制同样可能影响集成安装程序；**手动**操作会打开 Android 安装程序。

安装完成后，主屏幕会显示兼容应用的版本，以及有多少已安装应用通过它连接。点击其卡片可查看详情、重新安装内置副本或将其卸载。Porter 会先尝试通过自身服务卸载，失败时打开 Android 卸载程序。移除它会中断需要兼容支持的应用；直接支持 Porter 的应用不受影响。兼容性界面在打开期间会自动检查变化。
## Porter 和 Shizuku 可以同时运行吗？
{: #can-porter-and-shizuku-run-together }

可以。两者可以同时安装并运行。支持选择服务的应用一次只连接一个服务。

**Porter Compatibility 无法与 Shizuku 同时安装。** 它使用 Shizuku 的 Android 应用身份来兼容旧应用。Android 将它们视为同一应用的不同安装版本，而非两个独立应用。使用相同应用身份的分支也受此限制。

要切回 Shizuku，请卸载 Porter Compatibility 并重新安装 Shizuku。重启客户端应用，并在 Shizuku 中批准访问。你可以保留独立的 Porter 应用。

## 应用仍然要求 Shizuku
{: #an-app-still-asks-for-shizuku }

即使访问权限由 Porter 提供，旧应用的设置中也可能仍显示 Shizuku。这是正常现象。

兼容应用覆盖了常见的 Shizuku 发现方式。依赖特定 Shizuku 页面、内部组件或非常旧的 API 的应用可能需要更新。如果某个应用无法连接，请在 [Porter 问题报告](https://github.com/d4rken-org/porter/issues)中注明应用名称和版本。
