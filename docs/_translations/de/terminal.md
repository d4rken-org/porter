---
title: Terminal-Apps
lang: de
translation_key: terminal
language_name: Deutsch
description: So nutzt du Porters Debugging- oder Root-Zugriff in einer Terminal-App mit rish.
---
# Porter in einem Terminal verwenden
{: #use-porter-in-a-terminal }

Porter enthält **rish**, ein Startprogramm, das eine Terminal-Shell mit Porters Debugging- oder Root-Zugriff öffnet.

1. Starte Porter.
2. Öffne das Dreipunktmenü, wähle **Einstellungen** und dann **Porter in Terminal-Apps verwenden**.
3. Exportiere die Dateien in einen neuen Ordner. Kopiere sie dann mit deiner Terminal-App in deren privates Dateiverzeichnis.
4. Folge den Anweisungen zu Befehlen und Umgebungsvariablen in der installierten Porter-App. Verwende überall dort, wo danach gefragt wird, den tatsächlichen Paketnamen deiner Terminal-App.
5. Führe den angezeigten Befehl aus und bestätige die Zugriffsanfrage der Terminal-App in Porter.

Verwende die Dateien, die deine installierte Porter-Version exportiert hat. Führe das Startprogramm nicht direkt aus einem gemeinsam genutzten Speicherort wie Downloads aus. Android schränkt die Ausführung dort ein.

Ein vorhandenes Shizuku-rish-Startprogramm braucht möglicherweise [Porter Compatibility](compatibility.html). Ein frisch aus Porter exportiertes Startprogramm kann Porter direkt ansprechen.

Befehle laufen mit den Rechten des Dienstes: Debugging-Zugriff ist kein Root-Zugriff. Du kannst die Freigabe der Terminal-App in Porters Liste der autorisierten Apps entziehen.
