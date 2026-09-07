# API source dependency

The `api/` submodule points to [d4rken-org/porter-api](https://github.com/d4rken-org/porter-api). It contains the client adapter, compatible Shizuku SDK, Binder interfaces, shared server code and rish native code. The app's `settings.gradle` maps `aidl`, `rish`, `shared`, `api`, `provider`, `server-shared` and `client` to this source tree.

The fork starts from `thedjchi/Shizuku-API` commit `37ebcd3e45edf1d68975c70dd199350b434161f7`, retaining history and its MIT license. The Porter adapter retains Apache 2.0. The exact API revision for any app checkout is its Git submodule pin; inspect it with `git submodule status api`. Normal builds do not follow the API repository's branch tip. Do not use `git submodule update --remote` as routine build preparation.

## Local sibling repositories

The maintainer workspace uses:

```text
~/projects/porter/
  app/    # d4rken-org/porter
  api/    # d4rken-org/porter-api
```

The app still contains an `app/api/` submodule for independent clones. To develop against the sibling API checkout, put this in the app checkout's ignored `local.properties`:

```properties
api.useLocal=true
api.dir=../api
```

For a worktree, `api.dir` is relative to that worktree, so use an absolute path to the intended API checkout if necessary. Remove these two properties to validate the committed submodule pin. Before committing an app update, commit the API changes and check out that commit inside the app's submodule.

## Client SDK distribution

JitPack publishes the API repository's `client`, `api`, `provider`, `shared` and `aidl` modules. The client artifact depends on our other published modules, not on `dev.rikka.shizuku` SDK artifacts. The Porter app builds those modules directly from its pinned source. Only the legacy and terminal probe variants retain upstream SDK dependencies to exercise old-client compatibility.

SDK release numbers are independent of protocol versions. Keep public Java packages, AIDL names and Binder identifiers unchanged; artifact coordinates do not require class renaming. Gradle capability metadata reports a conflict when an integrating app includes both SDKs.

The [developer guide](../../docs/developers.md) is the canonical integration guide. Build and release steps for the SDK live in `porter-api/docs/publishing.md`.

## Publication order

Publish the API repository and the tested SDK release tag first, verify the JitPack build, then publish app commits that reference the API fork. An app submodule pin is not publicly fetchable until its API commit has been pushed. Local development can fetch it from the sibling repository without changing `.gitmodules` to a machine-specific path.
