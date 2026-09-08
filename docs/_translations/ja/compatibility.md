---
title: アプリの互換性
lang: ja
translation_key: compatibility
language_name: 日本語
description: Porter は対応アプリが使う Shizuku API をサポートしています。追加の互換アプリが必要かどうかは、アプリの接続方法によって決まります。
---
# アプリの互換性
{: #app-compatibility }

Porter は対応アプリが使う Shizuku API をサポートしています。追加の互換アプリが必要かどうかは、アプリの接続方法によって決まります。

| アプリの対応状況 | インストールするもの |
| --- | --- |
| Porter に直接対応 | Porter |
| Shizuku のみに対応 | Porter と Porter Compatibility。先に Shizuku を削除 |
| 両方に対応し、サービスを選択可能 | Porter をインストールし、アプリ内で Porter を選択 |

互換アプリは、既存の Shizuku 対応アプリが Porter を見つけるために使われます。サービスの起動、許可要求の表示、アクセス許可の管理は Porter が行います。必要なアプリを使う間は互換アプリを残してください。

## Shizuku から切り替える
{: #switch-from-shizuku }

Porter に直接対応するアプリでは、Shizuku を残して構いません。Porter を起動し、アプリ内で選択して新しい要求を許可します。再起動を求められた場合は Android の設定でアプリを強制停止し、開き直してください。

Shizuku のみに対応するアプリの場合：

1. Shizuku を停止して管理アプリをアンインストールします。Shizuku を使う他のアプリは残して構いません。
2. 同じリリースの Porter と Porter Compatibility の APK をインストールします。
3. Porter を起動します。
4. Android の設定でクライアントアプリを強制停止し、開き直して Shizuku 連携を有効にします。
5. Porter が表示するアクセス要求を許可します。

以前の Shizuku の許可は引き継がれません。Porter を使えるアプリをもう一度選んでください。

## Porter と Shizuku は同時に動作できますか？
{: #can-porter-and-shizuku-run-together }

はい。両方をインストールし、同時に動作させることができます。サービスを選べるアプリは、一度に 1 つのサービスに接続します。

**Porter Compatibility は Shizuku と同時にインストールできません。** 古いアプリに対応するため Shizuku の Android アプリ識別子を使っており、Android は両者を別々のアプリではなく、同じアプリの競合するインストールとして扱います。同じ識別子を使うフォークも同様です。

元に戻すには Porter Compatibility を削除して Shizuku を再インストールし、クライアントアプリを再起動して Shizuku でアクセスを許可してください。単独の Porter は残して構いません。

## アプリがまだ Shizuku を要求する
{: #an-app-still-asks-for-shizuku }

Porter がアクセスを提供していても、古いアプリの設定には Shizuku と表示される場合があります。これは正常です。

互換アプリは一般的な Shizuku の検出方法をカバーします。特定の画面、内部コンポーネント、非常に古い API に依存するアプリには更新が必要な場合があります。接続できないアプリがあれば、[Porter の問題報告](https://github.com/d4rken-org/porter/issues)にアプリ名とバージョンを記載してください。
