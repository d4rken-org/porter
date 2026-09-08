---
title: Porter
lang: ru
translation_key: index
language_name: Русский
description: Porter представляет собой минималистичный, поддерживаемый форк Shizuku, который предоставляет приложениям Android доступ через ADB с помощью API Shizuku. При желании можно использовать root.
---
# Доступ через ADB для ваших приложений
{: #adb-access-for-your-apps }

Porter представляет собой минималистичный, поддерживаемый форк [Shizuku](https://github.com/thedjchi/Shizuku), который предоставляет приложениям Android доступ через ADB с помощью API Shizuku. При желании можно использовать root.

Вы сами выбираете, каким приложениям разрешить доступ.

## Пользователям
{: #for-users }

1. Скачайте Porter из [GitHub Releases](https://github.com/d4rken-org/porter/releases).
2. Следуйте [инструкции по установке и запуску](/setup).
3. Откройте совместимое приложение и подтвердите его запрос доступа к Porter.

Требуется Android 7.0 или новее. На Android 11 и новее отладка по Wi-Fi позволяет запустить Porter без компьютера. На более старых устройствах нужен компьютер или root.

Дополнительная помощь: [совместимость приложений](/compatibility) и [решение проблем](/troubleshooting).

## Уже пользуетесь Shizuku?
{: #already-using-shizuku }

У Porter собственный идентификатор приложения, поэтому он может работать одновременно с Shizuku. Приложениям, которые распознают только Shizuku, нужен дополнительный компонент Porter Compatibility. Он заменяет установленное приложение Shizuku.

[Выберите подходящую конфигурацию](/compatibility).

## Зачем создан Porter
{: #why-porter-exists }

Я использую Shizuku в своих приложениях [SD Maid SE](https://github.com/d4rken-org/sdmaid-se) и [Butler](https://github.com/d4rken-org/butler). Когда исходное приложение Shizuku перестало активно поддерживаться, я создал Porter, чтобы приложениям, которым нужен ADB, оставалась доступна минималистичная, стабильная и поддерживаемая альтернатива.

## Разработчикам
{: #for-developers }

Другие разработчики также могут напрямую поддержать разрешение Porter. [Добавьте прямую поддержку Porter в своё приложение](/developers).

## Нужна помощь?
{: #need-help }

Начните с [решения проблем](/troubleshooting) или [сообщите об ошибке](https://github.com/d4rken-org/porter/issues).

Porter представляет собой независимое продолжение Shizuku, основанное на работе [RikkaApps](https://github.com/RikkaApps/Shizuku), [thedjchi](https://github.com/thedjchi/Shizuku) и их участников.
