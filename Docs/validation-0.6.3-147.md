# iOS 0.6.3 — build 147

Validated the changes from fork PR #23, merged as `b219af417`, including
`a9eb7438f` (`fix(ios): fill missing Simkl episode dates and refresh next up cache`).

## Changes reviewed

- Fill missing episode release dates from matching Simkl library entries, and
  continue preferring earlier Simkl dates over later addon dates.
- Load the persisted Simkl library before resolving dates. Network ID searches
  remain limited to upcoming episodes.
- Migrate the Next Up cache to version 1 so old episode dates are resolved again,
  retaining in-progress playback records.
- Increment the iOS build number from 146 to 147, retaining version 0.6.3.

## Validation

Toolchain: Xcode 27.0, iOS SDK 27.0, Kotlin 2.4.10, Java 17.

| Check | Result |
| --- | --- |
| Full shared/native iOS suite | 1535 passed; zero failures or skipped tests |
| App Store shared/native iOS suite | 1535 passed; zero failures or skipped tests |
| App Store arm64 simulator Debug build | Passed; version/build verified as 0.6.3 (147) |
| Simulator launch | Passed; profile picker displayed on iPhone 17 Pro, iOS 26.5 |
| Signed iPhone Release archive/export | Passed; Apple Distribution signature verified |
| Apple package validation | Passed with no errors |
| TestFlight upload and processing | Passed; VALID and IN_BETA_TESTING in Dev |

The suites include the new undated-episode and cache-migration regression tests.
Reports are retained locally under `build/validation-reports/147/`.

## Delivery target

- App: Nuvi0 (`6806071485`), bundle `com.nuvio.media.S8KM3NMGR4`.
- Version/build: 0.6.3 (147); the previous valid TestFlight build is 146.
- Existing internal tester group: Dev, with automatic access to all builds.
- Signed artifact location: `build/ios-testflight-0.6.3-147/export/Nuvio.ipa`.

Signing and upload reuse the existing private local App Store Connect
configuration; no credentials are stored in this repository.

The first export was interrupted by a lost network connection. The successful
retry reused the compiled archive on 2026-10-24. The exported IPA's version,
build number, bundle ID, signature, and absence of private CommonCrypto symbols
were checked before Apple validation.

## Confirmed delivery

- Verified on 2026-10-24T00:25:01.085Z.
- Release source commit: `4940ddfaa19d3c3ae0566d0a66f4ac4f86181ab3`.
- Apple build ID: `e93d4406-b69d-4e95-92b2-b1a5be3d2162`.
- Delivery UUID: `e93d4406-b69d-4e95-92b2-b1a5be3d2162`.
- Membership in the existing Dev group was verified through App Store Connect.
- IPA SHA-256: `fe2ee40e910acb0c7b5fb5357915c422b71fdf5d16727b92f0f1318060fcebd3`.
