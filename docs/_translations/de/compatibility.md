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
| Nur Shizuku | Porter und Porter Compatibility; Porter ersetzt ein installiertes Shizuku |
| Beide, mit einer Dienstauswahl | Porter; danach Porter in der App auswählen |

Die Begleit-App hilft bestehenden Shizuku-Apps, Porter zu finden. Porter selbst startet weiterhin den Dienst, zeigt Zugriffsanfragen an und verwaltet Freigaben. Lass die Begleit-App installiert, solange du Apps verwendest, die sie brauchen.

## Von Shizuku wechseln
{: #switch-from-shizuku }

Für eine App mit direkter Porter-Unterstützung kannst du Shizuku installiert lassen. Starte Porter, wähle es in der App aus und bestätige die neue Anfrage. Eine App, die mit Shizuku verbunden war, bleibt bis zu ihrem Neustart bei Shizuku. Nutze deshalb **Beenden erzwingen** in den Android-Einstellungen und öffne sie erneut.

Für eine App, die nur Shizuku unterstützt, enthält die FOSS-Version die passende Porter-Compatibility-APK:

1. Installiere und starte Porter. Lass Shizuku installiert, bis du den Wechsel geprüft hast.
2. Öffne **Shizuku-Kompatibilität** auf der Startseite oder in den **Einstellungen**.
3. Ist Shizuku installiert, wähle **Ersetzen**. Bestätige **Zu Porter wechseln**. Porter beendet Shizuku, ersetzt dessen App und übernimmt geeignete Zugriffsentscheidungen automatisch. Kann Porter den Dienst nicht beenden, fordert es dich auf, ihn in Shizuku zu beenden und es erneut zu versuchen.
4. Andernfalls wähle **Automatisch installieren**.
5. Kehre zur Client-App zurück. Bestätige den Zugriff, falls du keine bestehende Entscheidung importiert hast. Kann die App weiterhin keine Verbindung herstellen, nutze **Beenden erzwingen** in den Android-Einstellungen und öffne sie erneut.

Porter übernimmt automatisch die Entscheidungen, die sich gegen installierte Apps und deren aktuellen Zugriff prüfen lassen. Bestehende Porter-Entscheidungen haben Vorrang. Shizukus App-Einstellungen und Kopplung werden nicht übernommen. Lässt sich die Zugriffsdatenbank nicht lesen, kannst du fortfahren und die Apps erneut freigeben. Porter sichert geeignete Zugriffsentscheidungen, bevor Shizuku entfernt wird; schlägt die Installation fehl, versuche es erneut oder nutze **Manuell** und danach **Gespeicherte Freigaben importieren**.

Der integrierte Wechsel steht im primären Android-Nutzer zur Verfügung. Ist Shizuku für einen anderen Nutzer oder ein anderes Profil installiert, kümmere dich getrennt um diese Installation. Porter entfernt Apps anderer Nutzer nicht automatisch.

Die Begleit-APK bleibt als separater Download derselben Veröffentlichung verfügbar. Für die manuelle Installation beendest und deinstallierst du Shizuku, installierst Porter Compatibility und startest Porter. Nutze Porters integrierten Wechsel, um geeignete Zugriffsentscheidungen zu übertragen; das bloße Öffnen des Wechsel-Dialogs speichert keinen Import. Installationsbeschränkungen des Geräts können auch für den integrierten Installer gelten; die Aktion **Manuell** öffnet den Android-Installer.

Nach der Installation zeigt die Startseite die Version der Kompatibilitäts-App und wie viele installierte Apps über sie verbunden sind. Tippe auf ihre Karte, um Details zu sehen, die enthaltene Kopie erneut zu installieren oder sie zu deinstallieren. Porter versucht zuerst, über seinen Dienst zu deinstallieren, und öffnet den Android-Deinstaller, falls das fehlschlägt. Das Entfernen unterbricht Apps, die Kompatibilitätsunterstützung brauchen; Apps mit direkter Porter-Unterstützung laufen weiter. Die Kompatibilitätsseite prüft Änderungen automatisch, solange sie geöffnet ist.
## Können Porter und Shizuku gleichzeitig laufen?
{: #can-porter-and-shizuku-run-together }

Ja. Porter und Shizuku können gleichzeitig installiert sein und laufen. Eine App mit Dienstauswahl verbindet sich jeweils mit einem der beiden Dienste.

**Porter Compatibility kann nicht zusammen mit Shizuku installiert sein.** Die Begleit-App verwendet Shizukus Android-App-Identität, um ältere Apps zu unterstützen. Android behandelt sie als konkurrierende Installationen derselben App. Das gilt auch für Forks mit derselben Identität.

Um zurückzuwechseln, deinstalliere Porter Compatibility und installiere Shizuku erneut. Starte die betreffende App neu und erlaube ihren Zugriff in Shizuku. Porter selbst kannst du installiert lassen.

## Eine App verlangt weiterhin Shizuku
{: #an-app-still-asks-for-shizuku }

In den Einstellungen einer älteren App kann weiterhin Shizuku stehen, obwohl Porter den Zugriff bereitstellt. Das ist normal.

Die Begleit-App deckt die üblichen Methoden ab, mit denen Apps Shizuku finden. Apps, die bestimmte Shizuku-Bildschirme, interne Komponenten oder sehr alte APIs voraussetzen, brauchen möglicherweise ein Update. Falls eine App keine Verbindung herstellen kann, nenne ihren Namen und ihre Version in einer [Porter-Problemmeldung](https://github.com/d4rken-org/porter/issues).
