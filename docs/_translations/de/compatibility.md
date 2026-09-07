---
title: App-Kompatibilität
lang: de
translation_key: compatibility
language_name: Deutsch
description: Welche Apps Porter direkt unterstützen und wann du Porter Compatibility brauchst.
---
# App-Kompatibilität
{: #app-compatibility }

Porter unterstützt die Shizuku-APIs, die kompatible Apps verwenden. Ob du die optionale Begleit-App brauchst, hängt davon ab, wie sich die App verbindet.

| Was deine App unterstützt | Was du installieren solltest |
| --- | --- |
| Porter direkt | Porter |
| Nur Shizuku | Porter und Porter Compatibility; vorher Shizuku deinstallieren |
| Beide, mit einer Dienstauswahl | Porter; danach Porter in der App auswählen |

Die Begleit-App hilft bestehenden Shizuku-Apps, Porter zu finden. Porter selbst startet weiterhin den Dienst, zeigt Zugriffsanfragen an und verwaltet Freigaben. Lass die Begleit-App installiert, solange du Apps verwendest, die sie brauchen.

## Von Shizuku wechseln
{: #switch-from-shizuku }

Für eine App mit direkter Porter-Unterstützung kannst du Shizuku installiert lassen. Starte Porter, wähle es in der App aus und bestätige die neue Anfrage. Falls die App einen Neustart verlangt, nutze **Beenden erzwingen** in den Android-Einstellungen und öffne sie erneut.

Für eine App, die nur Shizuku unterstützt:

1. Stoppe Shizuku und deinstalliere dessen Verwaltungs-App. Die Apps, die du mit Shizuku nutzt, können installiert bleiben.
2. Installiere Porter und die Porter-Compatibility-APK aus derselben Veröffentlichung.
3. Starte Porter.
4. Nutze für die betreffende App **Beenden erzwingen** in den Android-Einstellungen, öffne sie erneut und aktiviere ihre Shizuku-Unterstützung.
5. Bestätige die Zugriffsanfrage, die Porter anzeigt.

Bisherige Shizuku-Freigaben werden nicht übertragen. Du entscheidest erneut, welche Apps Porter verwenden dürfen.

## Können Porter und Shizuku gleichzeitig laufen?
{: #can-porter-and-shizuku-run-together }

Ja. Porter und Shizuku können gleichzeitig installiert sein und laufen. Eine App mit Dienstauswahl verbindet sich jeweils mit einem der beiden Dienste.

**Porter Compatibility kann nicht zusammen mit Shizuku installiert sein.** Die Begleit-App verwendet Shizukus Android-App-Identität, um ältere Apps zu unterstützen. Android behandelt sie als konkurrierende Installationen derselben App. Das gilt auch für Forks mit derselben Identität.

Um zurückzuwechseln, deinstalliere Porter Compatibility und installiere Shizuku erneut. Starte die betreffende App neu und erlaube ihren Zugriff in Shizuku. Porter selbst kannst du installiert lassen.

## Eine App verlangt weiterhin Shizuku
{: #an-app-still-asks-for-shizuku }

In den Einstellungen einer älteren App kann weiterhin Shizuku stehen, obwohl Porter den Zugriff bereitstellt. Das ist normal.

Die Begleit-App deckt die üblichen Methoden ab, mit denen Apps Shizuku finden. Apps, die bestimmte Shizuku-Bildschirme, interne Komponenten oder sehr alte APIs voraussetzen, brauchen möglicherweise ein Update. Falls eine App keine Verbindung herstellen kann, nenne ihren Namen und ihre Version in einer [Porter-Problemmeldung](https://github.com/d4rken-org/porter/issues).
