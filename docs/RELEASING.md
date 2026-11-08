# Releasing

Releases are built, signed and published by `.github/workflows/release.yml`.

## One-time setup (signing key)

```sh
scripts/create-keystore.sh --upload     # needs the gh CLI, run inside this repo
```

or without `gh`: run `scripts/create-keystore.sh`, then add the four printed values as repository
secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`. **Back up the `.jks` and its password**: an update only installs over an
existing install if it is signed with the same key.

## Cut a release

```sh
git tag v1.2.3 && git push origin v1.2.3
```

The workflow then: runs the unit tests → builds the **R8-optimized, resource-shrunk** release APK
and AAB → verifies the APK signature → publishes a GitHub Release with the APK, AAB,
`SHA256SUMS` and the R8 `mapping.txt` (keep it: it de-obfuscates crash stack traces).

- `versionName` comes from the tag; `versionCode` = `major*1_000_000 + minor*1_000 + patch`.
- A tag with a suffix (`v1.2.3-rc1`) is published as a pre-release.
- Dry run: *Actions → Release → Run workflow* with `publish` unchecked (artifacts only).

## What "optimized" means here

| Setting | Where |
| --- | --- |
| R8 full mode: code shrinking, optimization, obfuscation | `isMinifyEnabled = true` (`app/build.gradle.kts`) |
| Unused resources removed | `isShrinkResources = true` |
| Optimizing default rules + readable stack traces | `proguard-rules.pro` |
| No dependency-info blob in APK/AAB | `dependenciesInfo { ... }` |
| Gradle build cache + parallel | `gradle.properties` |

CI (`ci.yml`) also builds the unsigned release variant on every push so an R8 problem is caught
before tagging.

## Debug builds and the committed debug key

`app/debug.keystore` is committed on purpose (alias `androiddebugkey`, password `android`). Every
debug build, local or CI, is signed with it, so a new debug APK installs **over** the previous one
and keeps your library. It is a public key for development only: never publish a build signed with
it. CI checks that each debug APK carries this key's fingerprint
(`04:AF:C9:44:88:55:2B:06:CF:15:E2:51:3E:76:A6:81:DD:4E:51:10:9E:CB:59:2A:4B:B2:79:6D:F9:48:58:02`).
If you ever regenerate the keystore, update `EXPECTED_SHA256` in `.github/workflows/ci.yml`.

## Local release build

```sh
SIGNING_KEYSTORE_PATH=$PWD/sharingan-release.jks SIGNING_STORE_PASSWORD=... \
SIGNING_KEY_ALIAS=sharingan SIGNING_KEY_PASSWORD=... ./gradlew assembleRelease
```
Without those variables you get an unsigned APK.
