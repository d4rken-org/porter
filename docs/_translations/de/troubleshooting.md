---
title: Probleme lösen
lang: de
translation_key: troubleshooting
language_name: Deutsch
description: Hilfe bei Problemen mit Porters Start, Kopplung, App-Verbindungen und APK-Installation.
---
# Probleme lösen
{: #troubleshooting }

## Porter läuft nicht
{: #porter-is-not-running }

Wenn du Debugging-Zugriff nutzt, ist es normal, Porter nach einem Geräteneustart erneut starten zu müssen. Öffne Porter und nutze deine [Startmethode](setup.html). Die Kopplung über drahtloses Debugging allein startet den Dienst noch nicht.

## Der automatische Start funktioniert nicht
{: #automatic-start-does-not-work }

Starte Porter nach der Installation einmal manuell, bevor du dich auf **Start beim Booten** verlässt. Ein erfolgreicher Start über Debugging erteilt die Android-Einstellungsberechtigung, die für spätere automatische Starts nötig ist. Falls eine Benachrichtigung `WRITE_SECURE_SETTINGS` erwähnt, starte Porter erneut über einen Computer und versuche es danach noch einmal.

Der automatische Start hängt weiterhin davon ab, ob Android Debugging-Zugriff bereitstellt und Porter im Hintergrund laufen lässt. Falls er scheitert, nutze eine manuelle Startmethode.

## Die drahtlose Kopplung wird nicht abgeschlossen
{: #wireless-pairing-does-not-finish }

- Lass das Gerät mit dem WLAN verbunden und prüfe, ob drahtloses Debugging aktiviert ist.
- Erlaube Porter Benachrichtigungen, damit du den Kopplungscode eingeben kannst. Erlaube auch den Zugriff auf Geräte in der Nähe oder das lokale Netzwerk, wenn Android danach fragt.
- Lass Androids Kopplungscode-Dialog geöffnet, während du den Code in Porters Benachrichtigung eingibst. Falls der Code abläuft, öffne einen neuen Kopplungsdialog.
- Wenn du Legacy-Paarung verwendest, übernimm den Kopplungsport aus dem Kopplungscode-Dialog. Verwende nicht den Verbindungsport auf der Hauptseite für drahtloses Debugging.
- Falls ein VPN oder eine Einschränkung im lokalen Netzwerk die Erkennung verhindert, versuche es in einem Netzwerk, das die Kommunikation zwischen Geräten erlaubt.

Wenn drahtloses Debugging auf deinem Gerät nicht verfügbar oder unzuverlässig ist, [starte Porter über einen Computer](setup.html#with-a-computer).

## Der Computer findet das Gerät nicht
{: #the-computer-cannot-find-the-device }

Führe `adb devices` aus. Steht dort `unauthorized`, entsperre das Gerät und bestätige die Debugging-Anfrage. Wird nichts angezeigt, prüfe USB-Debugging, probiere ein USB-Datenkabel und einen anderen USB-Anschluss aus und prüfe, ob dein Computer den USB-Treiber des Herstellers benötigt.

Falls der Startbefehl eine fehlende Datei meldet, kopiere einen aktuellen Befehl über **Befehl anzeigen** aus der installierten Porter-App.

## Porter stoppt immer wieder
{: #porter-keeps-stopping }

Prüfe zuerst, ob das Gerät neu gestartet wurde oder Android Debugging deaktiviert hat. Starte Porter bei Bedarf erneut.

Falls Porter stoppt, während das Gerät eingeschaltet bleibt, prüfe die Akku- und Hintergrund-App-Einstellungen des Herstellers für Porter. Erlaube die Ausführung im Hintergrund, falls diese Einstellungen sie einschränken. Netzwerkwechsel und Android-Anpassungen des Herstellers können den Debugging-Zugriff beeinflussen.

Melde wiederholte Abbrüche mit Gerätemodell, Android-Version, Startmethode und einer Beschreibung dessen, was kurz vor dem Stopp passiert ist.

## Eine App kann keine Verbindung herstellen
{: #an-app-cannot-connect }

1. Prüfe, ob Porter **Porter läuft** anzeigt.
2. Prüfe, [ob die App Porter Compatibility benötigt](compatibility.html).
3. Falls die App eine Dienstauswahl hat, wähle Porter. Nutze für die App **Beenden erzwingen** in den Android-Einstellungen und öffne sie erneut.
4. Aktiviere die Unterstützung in der App und bestätige Porters Anfrage.
5. Prüfe den Eintrag der App in Porters Liste der autorisierten Apps.

Wenn du die Begleit-App nutzt, müssen beide Porter-APKs aus derselben Veröffentlichungsquelle stammen und mit passenden Zertifikaten signiert sein. Ohne die Begleit-App können ältere Apps Porter nicht mehr verwenden.

## Android installiert eine APK nicht
{: #android-wont-install-an-apk }

Falls du **Porter Compatibility** installierst, deinstalliere zuerst Shizuku. Die Begleit-App kann eine anders signierte Shizuku-Installation nicht aktualisieren, obwohl Android dieselbe App-Identität erkennt.

Bei Porter selbst kann ein älterer Entwicklungsbuild eine andere Signatur als eine öffentliche Veröffentlichung haben. Android installiert die eine Version nicht über die andere. Eine Deinstallation entfernt auch die App-Daten. Notiere daher vorher deine Einrichtung. Installiere Porter aus der gewünschten Quelle neu und erlaube deinen Apps erneut den Zugriff.

## Zugriff ist erlaubt, aber ein Vorgang schlägt trotzdem fehl
{: #access-is-allowed-but-an-operation-still-fails }

Debugging-Zugriff ist eingeschränkter als Root. Android-Versionen und Hersteller setzen zusätzliche Grenzen. Porter kann nicht jede App-Funktion ermöglichen. Prüfe auch die Anforderungen der jeweiligen App.

Auf Xiaomi-/POCO-Geräten mit MIUI gibt es in den Entwickleroptionen möglicherweise einen separaten Schalter **USB-Debugging (Sicherheitseinstellungen)**. Nur gewöhnliches USB-Debugging einzuschalten kann Funktionen zur App-Verwaltung weiterhin einschränken. Aktiviere die zusätzliche Einstellung, wenn du diese Funktionen nutzen möchtest, und starte Porter danach neu. Name und Verfügbarkeit der Einstellung unterscheiden sich je nach Systemversion.

Manche OPPO-/OnePlus-Systeme haben in den Entwickleroptionen einen Schalter zur **Berechtigungsüberwachung (Permission monitoring)**, der den Debugging-Zugriff einschränkt. Ihn auszuschalten kann den Vorgang ermöglichen, verändert aber die Schutzeinstellung des Herstellers. Diese herstellerspezifischen Lösungsvorschläge stammen aus der [ursprünglichen Shizuku-Anleitung](https://shizuku.rikka.app/guide/setup/). Sie wurden mit Porter noch nicht auf physischen Geräten überprüft.

## Die angezeigte Version des laufenden Dienstes weicht ab
{: #the-version-shown-while-running-is-different }

Porters Info-Dialog zeigt die Version der Porter-App. Die Karte zum laufenden Dienst zeigt die kompatible Shizuku-Dienstversion. Diese Versionsnummern haben unterschiedliche Zwecke.

## Ein Problem melden
{: #report-a-problem }

Öffne über Porters Dreipunktmenü die **Einstellungen** und dann **Hilfe & Support**. Dort kannst du den Support per E-Mail kontaktieren, die [Discord-Community](https://discord.gg/5hXXgwKNgm) besuchen oder [ein Problem auf GitHub melden](https://github.com/d4rken-org/porter/issues).

Für ein Diagnoseprotokoll wählst du **Diagnoseprotokoll aufnehmen**, reproduzierst das Problem und tippst auf **Aufnahme beenden**. Wähle die gespeicherte Aufnahme im Kontaktformular aus oder teile sie unter **Gespeicherte Protokolle**. Protokolle bleiben auf deinem Gerät, bis du sie teilst. Sie können App-Namen, Gerätedaten und Aktionen enthalten, die über Porter ausgeführt werden.

Ergänze folgende Angaben:

- Porter-Version und ob Porter Compatibility installiert ist.
- Gerätemodell und Android-Version.
- Wie du Porter gestartet hast: drahtloses Debugging, Computer oder Root.
- Die betroffene App und ihre Version.
- Was du getan hast, was du erwartet hast und was tatsächlich passiert ist.

Prüfe Screenshots und Protokolle vor dem Anhängen auf persönliche Informationen. Gib keine drahtlosen Kopplungscodes oder privaten Schlüssel weiter.
