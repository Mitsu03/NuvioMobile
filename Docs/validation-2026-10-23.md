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
- Added unsigned iPhone builds to the iOS validation workflow and selected arm64
  explicitly for simulator builds, matching the declared Kotlin target.
- Removed a comma from an upstream test name, which Kotlin/Native rejects.
- Updated the legacy progress fixture to a real 20-minute episode at 94% progress.
  The previous one-second fixture is intentionally treated as a placeholder clip
  by the current completion policy; the completion assertions are unchanged.

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

## Native iOS validation after accepting the Xcode license

Toolchain: Xcode 27.0 (27A266a), iOS SDK 27.0, Kotlin 2.4.10, Java 17.

| Check | Full | App Store |
| --- | --- | --- |
| Debug simulator build, arm64 | Passed | Passed |
| Native/shared iOS tests | 1533 passed, zero failed/skipped | 1533 passed, zero failed/skipped |
| iPhone build | Debug passed, unsigned IPA generated | Release archive and signed export passed |
| Simulator launch | Passed: profile picker displayed on iPhone 17 Pro, iOS 26.5 | Passed: profile picker displayed on the same simulator |

The Full IPA is `build/ios-ipa/nuvio-0.6.3-full-debug.ipa`. This is an unsigned
sideload artifact, not the App Store distribution intended for TestFlight.

## Remaining external blocker

- GitHub CI: dispatch returns HTTP 422, `Actions has been disabled for this
  repository`, even though repository Actions permissions report enabled and the
  validation workflow reports active. Enabling the workflow and reapplying the
  enabled repository setting did not remove the refusal; no validation run started.
  Local iOS validation is proceeding independently.

After the GitHub restriction is resolved, run:

```sh
gh workflow run validate-mobile.yml -R Mitsu03/NuvioMobile --ref cmp-rewrite
```

The validation workflow does not sign releases or upload builds to an app store.
## TestFlight delivery

The separately requested delivery uses the App Store distribution and
`scripts/build-ios-appstore.sh`. Existing local credentials were recovered from
the prior configuration and verified against App Store Connect, without adding
credentials to the repository.

- App Store Connect record: `Nuvi0`, app ID `6806071485`.
- Bundle ID: `com.nuvio.media.S8KM3NMGR4`.
- Target: version `0.6.3`, build `146`. The previous uploaded build was `125`.
- Existing internal group `Dev` has automatic access to all builds.
- Signed Release archive/export passed with Apple Distribution signing. The
  exported binary passed the private CommonCrypto symbol check.
- Apple server validation completed with `VERIFY SUCCEEDED` and no errors.
- Upload completed with `UPLOAD SUCCEEDED` and no errors. Delivery UUID:
  `13709431-5426-488f-b95f-df12e6c43d8b`.
- Apple processing completed with `VALID`; internal state is `IN_BETA_TESTING`.
- Build membership in the existing `Dev` group was verified through App Store
  Connect. Its two existing testers have access to this build.
- Signed IPA: `build/ios-testflight-0.6.3-146/export/Nuvio.ipa`.

IPA SHA-256: `0a9648431a81339bcfc4362275a2af703398552a10066b386618ef7f49a698a6`.

App Store Connect connection metadata is stored locally in
`~/.appstoreconnect/nuvio.json` with private file permissions; the existing private
key remains in its original location and no credentials were committed.
