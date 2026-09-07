---
title: Terminal apps
lang: en
translation_key: terminal
language_name: English
---
# Use Porter in a terminal

Porter includes **rish**, a launcher that opens a terminal shell with Porter's debugging or root access.

1. Start Porter.
2. Open the overflow menu, choose **Settings**, then **Use Porter in terminal apps**.
3. Export the files to a new folder, then use your terminal app to copy them into its private files directory.
4. Follow the command and environment-variable instructions shown by the installed Porter app. Use your terminal app's actual package name wherever requested.
5. Run the displayed command and approve the terminal app's access request in Porter.

Use the files exported by your installed Porter version. Do not execute the launcher directly from shared storage such as Downloads; Android restricts execution there.

An existing Shizuku rish launcher may need [Porter Compatibility](compatibility.md). Exporting a fresh launcher from Porter lets it address Porter directly.

Commands run with the service's access: debugging access is not root. You can remove the terminal's approval from **Authorized applications** in Porter.
