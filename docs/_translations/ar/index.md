---
title: Porter
lang: ar
translation_key: index
language_name: العربية
description: Porter هو فرع بسيط ومستمر الصيانة من Shizuku، يتيح لتطبيقات Android الوصول عبر ADB باستخدام واجهات Shizuku البرمجية، مع دعم اختياري لصلاحيات الروت.
---
# وصول ADB لتطبيقاتك
{: #adb-access-for-your-apps }

Porter هو فرع بسيط ومستمر الصيانة من [Shizuku](https://github.com/thedjchi/Shizuku)، يتيح لتطبيقات Android الوصول عبر ADB باستخدام واجهات Shizuku البرمجية، مع دعم اختياري لصلاحيات الروت.

أنت تختار التطبيقات المسموح لها باستخدامه.

## للمستخدمين
{: #for-users }

1. نزّل Porter من [إصدارات GitHub](https://github.com/d4rken-org/porter/releases).
2. اتبع [دليل التثبيت والتشغيل](/setup).
3. افتح تطبيقًا مدعومًا ووافق على طلب وصوله إلى Porter.

يلزم Android 7.0 أو أحدث. في Android 11 أو أحدث، يمكنك تشغيل Porter دون حاسوب باستخدام تصحيح الأخطاء اللاسلكي. تحتاج الأجهزة الأقدم إلى حاسوب أو روت.

للمزيد: [توافق التطبيقات](/compatibility) و[حل المشكلات](/troubleshooting).

## هل تستخدم Shizuku بالفعل؟
{: #already-using-shizuku }

يمتلك Porter هوية تطبيق مستقلة ويمكنه العمل بجانب Shizuku. تحتاج التطبيقات التي لا تتعرف إلا على Shizuku إلى التطبيق المرافق الاختياري Porter Compatibility، الذي يحل محل تطبيق Shizuku المثبّت.

[اختر الإعداد المناسب لتطبيقاتك](/compatibility).

## لماذا أُنشئ Porter
{: #why-porter-exists }

أستخدم Shizuku في تطبيقيّ [SD Maid SE](https://github.com/d4rken-org/sdmaid-se) و[Butler](https://github.com/d4rken-org/butler). بعد توقف الصيانة النشطة لتطبيق Shizuku الأصلي، أنشأت Porter لتوفير بديل بسيط ومستقر ومستمر الصيانة للتطبيقات التي تحتاج إلى وصول ADB.

## للمطورين
{: #for-developers }

نرحب بإضافة دعم مباشر لإذن Porter في تطبيقات المطورين الآخرين. [أضف دعم Porter المباشر إلى تطبيقك](/developers).

## هل تحتاج إلى مساعدة؟
{: #need-help }

ابدأ بصفحة [حل المشكلات](/troubleshooting)، أو [أبلغ عن مشكلة](https://github.com/d4rken-org/porter/issues).

Porter امتداد مستقل لمشروع Shizuku، مبني على عمل [RikkaApps](https://github.com/RikkaApps/Shizuku) و[thedjchi](https://github.com/thedjchi/Shizuku) والمساهمين معهما.
