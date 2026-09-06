# Manticord Release Downloads

Cloudflare Worker exposing published, non-prerelease `build-N` releases' `app-release.apk`
assets and validated build metadata from `git.hoodnet.work/firecord/microcord`. The repository,
raw release notes and other assets remain private. The selected APKs become publicly downloadable.

Primary address: https://microcord.mantikafasi.dev

Fallback address: https://manticord-updates.mantikafasi.workers.dev

## Credentials

Create a fine-grained token on `git.hoodnet.work`, scoped to `firecord/microcord` with
repository Contents read-only permission. From the repository root:

```sh
wrangler login
wrangler secret put GITHUB_TOKEN --config tools/update-worker/wrangler.toml
```

Enter the token at Wrangler's prompt. Do not put it in source, Wrangler `vars`, chat,
or the Android app. Local `.env*` and `.dev.vars*` files are ignored. The Worker returns
503 until the secret is present. No GitHub login is required for users downloading APKs.

## Endpoints

- `GET /latest.json`: `release`, `publishedAt`, `apkUrl`, `sha256`, `size`, plus `commitSha`,
  `commitTimestamp`, `versionCode`, `versionName`, `packageName` when a valid manifest is attached.
- `GET /releases/build-163.apk`: that exact published release's APK, not whichever is latest.
- Both routes support HEAD. Unknown routes, extra query parameters and other methods are rejected.

The feed caches only sanitized release metadata for 60 seconds (internal cache schema `v2`).
It never caches the raw release, raw manifest, credentials or media URLs. Downloads use fresh GitHub
redirects restricted to the observed `https://media.git.hoodnet.work` origin, or stream a
binary 200/206 response. Redirects are not cached; upstream credentials/cookies are never
forwarded to the user or to the download host. Single byte ranges are forwarded for resuming.
No APK buffering, storage bucket, database or custom URL-signing service is needed.

`release` is a GitHub tag, **not Android versionCode**. Historical `build-163` has no manifest:
its feed retains the original five fields and its versioned APK remains downloadable. All five
new build fields are absent, not fabricated from `target_commitish`, publication time or tag
number. The client must report a legacy feed and not auto-prompt in that case. No historical
release needs to be edited. An attached but invalid or unavailable manifest fails closed (503),
rather than silently becoming a legacy feed.

## Build Identity

Both local and CI builds generate these constants in `com.discord.BuildConfig`:

| Constant | Type | Source |
| --- | --- | --- |
| `UPDATE_COMMIT_SHA` | String | Full 40-hex git HEAD SHA, lowercase; `MANTICORD_COMMIT_SHA` must be full and match HEAD if set |
| `UPDATE_COMMIT_TIMESTAMP` | long (Kotlin Long) | That commit's committer time, `git show --format=%ct`, in epoch seconds |
| `UPDATE_BUILD_ID` | String | Existing `MANTICORD_BUILD_ID`, or `local` |

Android `versionCode = UPDATE_COMMIT_TIMESTAMP - 1577836800` (2020-01-01T00:00:00Z).
Configuration fails unless the result is in `1..2147483647`; no fallback to versionCode 1,
CI run number or the build machine's clock. `versionName` remains `1.0`. Git and the checked-out
commit must be available even when the SHA environment variable is set. The SHA identifies the
checkout, not uncommitted edits; the APK digest identifies the exact built bytes.

This is monotonic for normal trunk commits with increasing committer timestamps, not a promise
for backdated history. Same-second/different-commit releases can share a versionCode. The client
allows a nondecreasing package version only when the source timestamp is strictly newer (and
the full SHA differs); equal timestamps do not prove ordering and must not auto-prompt. Release
publication time and build ID are display information, not source ordering. Clients must verify
the downloaded SHA-256, size, package/version identity and use Android's signature-checked
installer. The current signing key/configuration is unchanged.

## Release Manifest

From the repository root, the release pipeline runs:

```sh
chmod +x gradlew
bash scripts/build.sh :app:assembleRelease :app:generateReleaseUpdateManifest
```

Task `:app:generateReleaseUpdateManifest` depends on release assembly and consumes AGP's APK
artifact/output metadata. It requires the single produced `app-release.apk`, checks the package
and version, and streams SHA-256 over that final signed APK without loading it into memory.
Output: `app/build/outputs/update/release/update-manifest.json`.

The UTF-8 JSON object contains:

| Field | Contract |
| --- | --- |
| `commitSha` | Full 40-hex build commit SHA, not a branch name or abbreviated hash |
| `commitTimestamp` | Integer committer epoch seconds |
| `versionCode` | Positive Android int, exactly `commitTimestamp - 1577836800` |
| `versionName` | Nonblank string from the APK output metadata |
| `sha256` | 64-hex digest of the final signed APK |
| `size` | Positive APK byte count |
| `packageName` | Exactly `dev.mantikafasi.manticord` |

CI keeps pushes as debug artifact uploads only. Manual `workflow_dispatch` builds release APKs
and publishes the manifest beside `app-release.apk`, failing if either file is missing. The
release's `target_commitish` is pinned to `github.sha` to avoid branch movement during a build.
There are no schedules or automatic releases. No GitHub credential is embedded in either file.

The Worker fetches only the exact `update-manifest.json` asset ID listed on the same release as
the APK. It permits a direct 200 response or one 302 to the observed media origin, with manual
redirect handling and no auth/cookies on the media fetch. Subsequent redirects fail closed. Both
the declared asset size and streamed response are bounded to 16 KiB; body length must match the
manifest asset size. Parsed APK hash/size must match GitHub's APK asset digest/size, and all build
fields must satisfy the table above. Additional manifest fields are ignored, not exposed.

## Verify And Deploy

From this directory:

```sh
node --test worker.test.mjs
wrangler deploy --dry-run
wrangler deploy
```

The Wrangler config pins the deployment account and the user-approved
`microcord.mantikafasi.dev` Workers Custom Domain. Confirm any additional hostname before
changing routes. Keep these public endpoints free of browser-only access challenges.

The release API's digest must be present. Drafts, prereleases, missing APKs, missing digests,
unexpected redirect hosts and upstream errors fail closed. Changes to Enterprise download
hosting require verifying the new origin before changing the allowlist. Public request abuse
can still consume GitHub API quota; configure Cloudflare rate limiting if needed rather than
building an in-memory limiter. Signed redirect URLs are temporary bearer URLs: don't log them.
