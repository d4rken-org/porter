---
title: Porter
lang: ja
translation_key: index
language_name: 日本語
description: Porter は Shizuku をもとにした、シンプルで継続的にメンテナンスされているフォークです。Shizuku API を通じて Android アプリに ADB アクセスを提供し、必要に応じて root にも対応します。
---
# アプリに ADB アクセスを
{: #adb-access-for-your-apps }

Porter は [Shizuku](https://github.com/thedjchi/Shizuku) をもとにした、シンプルで継続的にメンテナンスされているフォークです。Shizuku API を通じて Android アプリに ADB アクセスを提供し、必要に応じて root にも対応します。

アクセスを許可するアプリは、あなたが選びます。

## ユーザー向け
{: #for-users }

1. [GitHub Releases](https://github.com/d4rken-org/porter/releases) から Porter をダウンロードします。
2. [インストールと起動のガイド](/setup)に従って設定します。
3. 対応アプリを開き、Porter へのアクセス要求を許可します。

Android 7.0 以降が必要です。Android 11 以降では、ワイヤレスデバッグでパソコンなしに起動できます。それ以前の端末にはパソコンまたは root が必要です。

詳しくは[アプリの互換性](/compatibility)と[トラブルシューティング](/troubleshooting)をご覧ください。

## すでに Shizuku をお使いですか？
{: #already-using-shizuku }

Porter は独立したアプリ識別子を持ち、Shizuku と同時に動作できます。Shizuku のみを認識するアプリには、追加の Porter Compatibility が必要です。この互換アプリは、インストール済みの Shizuku アプリを置き換えます。

[アプリに合った構成を確認する](/compatibility)。

## Porter を作った理由
{: #why-porter-exists }

私は自分のアプリである [SD Maid SE](https://github.com/d4rken-org/sdmaid-se) と [Butler](https://github.com/d4rken-org/butler) で Shizuku を使っています。オリジナルの Shizuku が積極的にメンテナンスされなくなったため、ADB アクセスを必要とするアプリに、シンプルで安定した、継続的にメンテナンスされる選択肢を提供するために Porter を作りました。

## 開発者向け
{: #for-developers }

他のアプリ開発者による Porter 権限への直接対応も歓迎します。[アプリに Porter の直接サポートを追加する](/developers)。

## お困りですか？
{: #need-help }

[トラブルシューティング](/troubleshooting)を確認するか、[問題を報告](https://github.com/d4rken-org/porter/issues)してください。

Porter は Shizuku を独立して継承するプロジェクトで、[RikkaApps](https://github.com/RikkaApps/Shizuku)、[thedjchi](https://github.com/thedjchi/Shizuku)、およびその貢献者の成果をもとにしています。
