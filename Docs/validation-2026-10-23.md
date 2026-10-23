# iOS integration validation — 2026-10-23

Integrated upstream `NuvioMedia/NuvioMobile` commit
`e6c399e014939cd63dd85a3fc5d248c2bacccbe4` (0.6.3, build 146) into the fork's
`cmp-rewrite` branch, starting from fork commit
`188275689d265e36c65131ac9c7763d74f99a7fa`.

The upstream commit is an ancestor of the integrated branch. Fork features were
retained when resolving the eight conflicting files, including filler labels,
Simkl release dates, iOS subtitle sync, playback speed restoration, and the
immediate next-episode action. The merged player also retains upstream's party
controls, engine switching, and media servers.

## Corrections

- Replaced the filler's JVM-only `RegexOption.DOT_MATCHES_ALL` with a portable
  multiline pattern and added a regression test.
- Preserved upstream's static poster URL behavior and added coverage for encoded
  placeholders and missing required IDs. The upstream poster refactor already
  resolves the mismatch found in the older fork revision.
- Removed orphan gitlinks for `libass-android`, `vendor/TorrServer`, and
  `vendor/quickjs-kt`. These empty directories had no configuration or code
  references. MPVKit remains pinned to upstream's selected commit.
- Made the iOS engine cache check its version, so an existing 0.1.2 framework
  cannot silently satisfy a build that requires 0.1.5.
- Added `Validate iOS` CI for full/appstore simulator builds and shared/native
  tests, using the runner's selected Xcode toolchain. Android validation is outside
  the requested scope.

## Passed locally

- `:composeApp:compileCommonMainKotlinMetadata` with iOS appstore distribution
  settings. This validates common code, not the iOS implementation.
- 52 isolated JVM tests for filler parsing/mapping and custom poster URLs. The
  harness compiled the real implementations, using extracted numbering/ID helpers
  and model dependencies outside the Android/iOS build. This is not the full suite.
- XML syntax for all 93 resource XML files; no duplicate string names per qualifier.
- All GitHub workflows checked with actionlint 1.7.12.
- iOS Info.plist and Xcode project syntax; shell script syntax; Git whitespace checks.
- Engine 0.1.5 download checksum and device/simulator libraries, cache reuse with a
  matching version, and re-download when the cached version is outdated.
- Clean working tree after pushing; upstream ancestry confirmed.

## Blocked checks

Full iOS builds and test suites have **not passed** in this validation:

- iOS simulator build: Xcode refuses to run until its license is reviewed and
  accepted by the machine owner (`sudo xcodebuild -license`).
- GitHub CI: dispatch returns HTTP 422, `Actions has been disabled for this
  repository`, even though repository Actions permissions report enabled and the
  validation workflow reports active. Enabling the workflow and reapplying the
  enabled repository setting did not remove the refusal; no validation run started.

After the GitHub restriction is resolved, run:

```sh
gh workflow run validate-mobile.yml -R Mitsu03/NuvioMobile --ref cmp-rewrite
```

Alternatively, after accepting the Xcode license, use the iOS commands in
`.github/workflows/validate-mobile.yml` locally.
The workflow does not sign releases or upload builds to an app store.
