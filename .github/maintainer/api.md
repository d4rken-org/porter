# API source dependency

The `api/` submodule contains Shizuku's SDK, Binder interfaces, shared server code and rish native code. `settings.gradle` includes its `aidl`, `rish`, `shared`, `api`, `provider` and `server-shared` modules. It is a source dependency compiled into the build, not another app users need to install or a service contacted at runtime.

The current source is [thedjchi/Shizuku-API](https://github.com/thedjchi/Shizuku-API), pinned to commit `37ebcd3e45edf1d68975c70dd199350b434161f7`. A normal submodule checkout uses that commit. The `branch = master` setting only affects explicit remote updates; it does not make builds follow new commits automatically. Do not run `git submodule update --remote` as routine build preparation.

The recommended next ownership step is to mirror this repository under `d4rken-org`, retain its history and MIT license, and change only the submodule URL while preserving the tested commit. The destination must exist and contain that commit before switching. No API mirror has been created by this change.

Vendoring the files into Porter is another option, but makes upstream history and future comparisons less convenient. Removing the submodule without replacing its source would break the build. Renaming the Java packages or AIDL identifiers would also break client compatibility; Porter's app identity does not require those changes.

The consumer adapter separately depends on the published `dev.rikka.shizuku:api` and `provider` 13.1.5 artifacts. Mirroring the source submodule does not replace or republish those dependencies.
