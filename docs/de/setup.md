---
title: Installieren und starten
lang: de
translation_key: setup
language_name: Deutsch
description: So installierst du Porter und startest den Dienst über drahtloses Debugging, einen Computer oder Root.
---
# Porter installieren und starten

Porter befindet sich in Entwicklung. Eine öffentliche Version gibt es noch nicht. Sobald sie verfügbar ist, findest du die Downloads unter [GitHub Releases](https://github.com/d4rken-org/porter/releases).

## Installieren
{: #install }

1. Lade die Porter-APK im Bereich **Assets** der Veröffentlichung herunter. Die ZIP- und TAR-Dateien mit dem Quellcode sind keine Android-Apps.
2. Öffne die APK auf deinem Gerät. Falls Android danach fragt, erlaube deinem Browser oder Dateimanager, Apps aus dieser Quelle zu installieren.
3. Öffne Porter.

Für Apps, die Porter direkt unterstützen, reicht Porter allein. Unterstützt eine App nur Shizuku, brauchst du zusätzlich **Porter Compatibility** aus derselben Veröffentlichung. Deinstalliere vorher Shizuku: Die Kompatibilitäts-App kann nicht gleichzeitig mit Shizuku installiert sein. Lass sie installiert, solange du solche Apps mit Porter verwendest. Weitere Hinweise stehen im [Kompatibilitätsleitfaden (Englisch)](../compatibility.md).

## Startmethode auswählen
{: #choose-how-to-start }

| Dein Gerät | Startmethode |
| --- | --- |
| Android 11 oder neuer mit drahtlosem Debugging | [Drahtloses Debugging](#wireless-debugging) |
| Android 7.0 oder neuer und ein Computer | [USB-Debugging](#with-a-computer) |
| Bereits gerootet | [Root](#root) |

## Drahtloses Debugging
{: #wireless-debugging }

Du brauchst eine WLAN-Verbindung und Android 11 oder neuer. Manche Hersteller schränken drahtloses Debugging ein. Falls es auf deinem Gerät nicht verfügbar ist, nutze einen Computer. Die Bezeichnungen in den Einstellungen können je nach Gerät abweichen.

1. Aktiviere die **Entwickleroptionen** in den Android-Einstellungen. Meist öffnest du dafür **Über das Telefon** und tippst siebenmal auf **Build-Nummer**.
2. Aktiviere in den Entwickleroptionen **USB-Debugging** und **Drahtloses Debugging** beziehungsweise **Wireless-Debugging**. Bestätige gegebenenfalls die Abfrage für das WLAN.
3. Tippe in Porter unter **Über Wireless-Debugging starten** auf **Kopplung**. Erlaube Benachrichtigungen und, falls angefragt, den Zugriff auf Geräte in der Nähe oder das lokale Netzwerk.
4. Öffne in Android die Einstellungen für **Drahtloses Debugging** und wähle **Gerät mit Kopplungscode koppeln**. Lass den Dialog geöffnet.
5. Klappe Porters Kopplungsbenachrichtigung auf und gib den von Android angezeigten Code ein. Warte, bis die Kopplung erfolgreich abgeschlossen ist.
6. Kehre zu Porter zurück und starte den Dienst im Abschnitt für Wireless-Debugging.
7. Prüfe, ob Porter **Porter läuft** anzeigt.

Wenn Porter stoppt, bleiben die Debugging-Einstellungen von Android aktiviert. Du kannst sie in den Entwickleroptionen ausschalten, wenn du keinen Debugging-Zugriff mehr brauchst.

Die Kopplung ist normalerweise nur einmal nötig. Den Dienst zu starten ist ein eigener Schritt, den du nach einem Neustart des Geräts wiederholen musst. Falls Android die Kopplung vergisst, führe die Schritte erneut aus.

Wenn du in Porter **Legacy-Paarung** aktiviert hast, warte im Dialog auf die Erkennung des Kopplungsdienstes und gib den Code dort ein. Falls nach einem Port gefragt wird, verwende den Kopplungsport aus Androids Kopplungscode-Dialog. Er unterscheidet sich vom Verbindungsport auf der Hauptseite für drahtloses Debugging.

## Mit einem Computer
{: #with-a-computer }

1. Installiere Googles [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) auf deinem Computer.
2. Aktiviere auf dem Android-Gerät die Entwickleroptionen und **USB-Debugging**.
3. Verbinde das Gerät mit einem USB-Datenkabel. Entsperre es und bestätige die Debugging-Verbindung. Erlaube den Zugriff nur Computern, denen du vertraust.
4. Öffne auf dem Computer ein Terminal im Platform-Tools-Ordner und führe `adb devices` aus. In Windows PowerShell verwende `./adb.exe devices`; unter macOS oder Linux `./adb devices`, falls ADB nicht im PATH liegt.
5. Tippe in Porter im Abschnitt zum Starten über einen Computer auf **Befehl anzeigen**. Führe genau diesen Befehl auf deinem Computer aus. Passe bei Bedarf den Aufruf von `adb` wie im vorigen Schritt an.
6. Prüfe, ob Porter **Porter läuft** anzeigt. Danach kannst du das USB-Kabel trennen.

Sind mehrere Geräte verbunden, füge direkt nach `adb` den Zusatz `-s DEVICE_SERIAL` ein. Verwende die Seriennummer, die `adb devices` für das Gerät mit Porter anzeigt.

Lass dir nach einem Update oder einer Neuinstallation von Porter einen neuen Befehl anzeigen. Der darin enthaltene Pfad kann sich ändern. Verwende keinen Startbefehl aus Shizuku oder einer anderen Installation.

## Root
{: #root }

Diese Methode ist für Geräte gedacht, auf denen Root-Zugriff bereits funktioniert. Durch die Installation von Porter wird dein Gerät nicht gerootet.

1. Öffne Porter und starte den Dienst im Abschnitt für gerootete Geräte.
2. Bestätige Porters Anfrage in deiner Root-Verwaltung.
3. Prüfe, ob Porter **Porter läuft** und als Startmodus Root anzeigt.

Stoppe den laufenden Porter-Dienst, bevor du zwischen Root und Debugging-Zugriff wechselst.

## Einer App Zugriff erlauben
{: #allow-an-app }

Öffne die gewünschte App, aktiviere dort die Unterstützung für Porter oder Shizuku und bestätige Porters Zugriffsanfrage. Erlaube den Zugriff nur Apps, denen du vertraust: Sie können Aufgaben mit Porters Debugging- oder Root-Rechten ausführen.

Um den Zugriff zu entziehen, öffne in Porter die Liste der autorisierten Apps und schalte die Berechtigung für die betreffende App aus. Porter und Shizuku verwalten ihre Freigaben getrennt.

Falls die App eine Dienstauswahl hat, wähle dort Porter. Folge den Anweisungen der jeweiligen App. Wenn ein Wechsel einen Neustart der App erfordert, nutze **Beenden erzwingen** in den Android-App-Einstellungen und öffne die App erneut.

## Porter aktualisieren
{: #update-porter }

Installiere die neuere APK über die vorhandene App und starte Porter erneut. Falls du Porter Compatibility verwendest, aktualisiere auch diese App aus derselben Veröffentlichung. Android verlangt für Updates passende Signaturen. Wenn die Installation scheitert, findest du Hilfe unter [Installationsprobleme (Englisch)](../troubleshooting.md#android-wont-install-an-apk).
