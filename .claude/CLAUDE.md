# Porter

Start from the recorded upstream baseline and preserve the pinned API submodule unless an API change is explicitly needed.

The standalone manager owns only Porter permissions. The optional compatibility companion owns the original package and client permission. Keep their signing identities aligned and keep legacy grants gated by the verified companion.

Preserve public Shizuku protocol identifiers. Do not globally replace Java packages, AIDL names or Binder keys.

Keep Porter setup instructions app-neutral. The project motivation may name and link the maintainer's own apps. Setup instructions and supported-version information for specific client apps belong in those apps' own documentation.

Write public documentation for the published product. The first repository/site publication will include a release; do not add pre-launch availability notices.

Support independent Porter and Shizuku services. Do not kill the original service or silently restart/reconfigure shared ADB transports.

Use isolated Android emulators with `-no-audio`. Do not adopt existing devices. Obtain the requested Claude review before committing significant changes.

Final artwork, production key custody and public repository ownership belong to the maintainer. Development builds use temporary artwork and development signing. Do not publish or configure external destinations without the corresponding authorization.

Keep agent context under `.claude/`; do not add top-level AGENTS files or directories. Use only DebugBadger or ADB for device interaction. Never control the host mouse or keyboard.
