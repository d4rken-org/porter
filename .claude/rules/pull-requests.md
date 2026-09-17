# Pull request labels

Read the actual diff against the PR's base before choosing labels. Fetch the exact names with
`gh label list --repo d4rken-org/porter --limit 100 --json name` and use them verbatim. Never invent
a label. If the right one does not exist, omit it and say so rather than substituting a near match.

## The one mandatory label

Every PR carries exactly one type label:

- `bug`: restores intended app or tooling behavior.
- `enhancement`: adds or improves behavior, usability, performance or supported capabilities.
- `documentation`: changes explanatory content or docs presentation only.
- `Chore`: refactors, tests, cleanup, and routine build or dependency maintenance.

Pick the principal outcome for mixed work. Supporting documentation does not add a second type.
Test-only work is `Chore`; a fix that ships with regression tests is `bug`. A broken CI job repaired
is `bug`, routine CI maintenance is `Chore`.

## Labels CI already applies

`.github/workflows/pr-labeler.yml` applies every label listed in `.github/labeler.yml` from the paths
a PR touches: the `c:` components, `Translation`, `Google Play` and `Build process`. Do not apply
those by hand and do not prune them. A component label means the area is touched, including its tests
and tooling; it does not claim the area's behavior changed.

To correct a durable mapping error, fix `labeler.yml`. Not every mismatch is a glob error: the
workflow reads its config from the base branch, so a mapping change only takes effect after it merges,
and because it never removes labels, one can survive after its matching file leaves the diff.

## Labels the agent adds

Apply these when the diff establishes them, not because a path looks related:

- `Root`: changes root startup, superuser integration or root-mode behavior. Porter's sources do not
  isolate root work into its own directory, which is why this is not a glob.
- `General UI/UX`: substantive presentation, navigation or accessibility work. Not every Activity or
  Compose edit qualifies. A permission-logic fix is `c: Apps`; a pairing-logic fix is `c: Setup`.
- `ROM: *`: changes behavior that depends on that ROM or platform. The reporter's device alone is not
  evidence.
- `api: NN ...`: changes behavior at an explicitly targeted Android release or compatibility boundary.
  For a new `SDK_INT >= N` branch, apply the label for N and do not expand it into every later
  release. Add a second version only when the diff targets it separately. A feature's minimum Android
  version, the SDK build settings, and the version of the test device do not qualify on their own.

## Labels the agent never applies

`needs info/repro 🤔`, `Out of scope` and `Device specific` are human issue triage. Do not copy labels
wholesale from a linked issue, and do not remove a label a human added.

## API submodule updates

The Gradle modules under the `api` submodule are checked out, not vendored, so this repository holds
no source tree for them. A submodule bump appears in the diff as the single path `api`, and CI labels it
`Build process`. Inspect the referenced commits to choose the type: a routine pointer update is `Chore`,
while a deliberate integrated fix or feature is `bug` or `enhancement`. Do not invent `api/**` paths
from files inside the submodule, and do not use porter-api's own labels such as `c: Protocol` or
`c: SDK` here.
