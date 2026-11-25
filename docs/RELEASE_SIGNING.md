# WavTape release signing

## GitHub setup

Keep `main` as the default development branch. Google AI Studio pushes directly
to `main`, and Codex can use regular PRs there. Keep the workflow definition on
`main` so GitHub displays the manual **Run workflow** control.

In **Settings → Rules → Rulesets**, configure an active branch ruleset targeting
`release`. Require a pull request before merging with **zero** mandatory
approvals. Block force pushes and branch deletion and leave the bypass list
empty. The solo maintainer reviews each `main` → `release` PR before merging it.

In **Settings → Environments → release-signing**, choose **Selected branches and
tags** and allow exactly one **Branch** named `release`. Remove any policy for
`main`, tags, or other branches. Keep the signing secrets at environment scope.

| Environment secret | Value |
| --- | --- |
| `WAVTAPE_KEYSTORE_BASE64` | Base64 of the permanent `WavTape-release.p12` keystore |
| `WAVTAPE_STORE_PASSWORD` | PKCS12 store password |
| `WAVTAPE_KEY_ALIAS` | `wavtape-release` |
| `WAVTAPE_KEY_PASSWORD` | Private key password |

Set the environment variable `WAVTAPE_SIGNER_SHA256` to the SHA-256 fingerprint
of the permanent signing **certificate**. Use 64 hexadecimal digits, with
optional colons between bytes. Obtain it from your trusted copy of the
certificate. Preserve the same keystore and certificate for every production
release so Android can verify updates.

## Verify release rulesets before dispatch

GitHub's ruleset API may omit `bypass_actors` for callers without ruleset write
access. The workflow uses the public API without an administrator token. It
rejects any disclosed nonempty bypass list and checks the active release rules
and the environment allowlist on every run. [GitHub ruleset API documentation](https://docs.github.com/en/rest/repos/rules#get-a-repository-ruleset)

Before dispatching, as a repository administrator, open **Settings → Rules →
Rulesets** and inspect the **Bypass list** of every active ruleset that applies
to `release`, including inherited rulesets. Confirm that each list is empty.
If you cannot inspect a list or it contains an entry, resolve that in GitHub
before dispatching.

After that check, select **I verified in GitHub Rulesets that every active
ruleset applying to release has an empty bypass list** in the dispatch form
(`bypass_lists_checked`). If GitHub withholds a list, preflight names the ruleset
in a warning and requires this confirmation. The confirmation applies to that
dispatch. Check the lists again before each new dispatch and after any ruleset
change. Actions cannot verify the administrator's inspection or detect changes
to an undisclosed list after that inspection.

## Build and download

1. For a new release, increment `versionCode` in `app/build.gradle.kts` on
   `main`. Preserve the application ID `com.aistudio.xtremeplayer.vkmz`.
2. Open a PR with `release` as the base and `main` as the compare branch.
   Review the changes, then merge the PR manually. Merge the workflow definition
   through this same process the first time you set it up.
3. Open **Actions → WavTape Release → Run workflow**, choose `release`, confirm
   your bypass-list verification, and start the run. Runs dispatched from
   `main` skip every job.
4. Open the successful run and download **WavTape-release-apk** under
   **Artifacts**. Extract `app-release.apk`. Only this verified artifact is
   intended for distribution.

Gradle builds a non-debuggable, unsigned APK on a runner with no signing secrets.
The unsigned signing input is a one-day transfer artifact. On a separate runner,
the signing job downloads that input by artifact ID and uses the Android SDK
tools. It decodes the keystore into a restricted temporary directory and removes
it on success or failure. The workflow uploads the final APK after verification.
GitHub Release publication remains a separate manual action.

## Verify a downloaded APK

With Android SDK Build-Tools 36.0.0 installed, run:

```bash
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs app-release.apk
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging app-release.apk
```

`apksigner` must succeed and report one signer. Compare its
`Signer #1 certificate SHA-256 digest` with the trusted `WAVTAPE_SIGNER_SHA256`
value, ignoring case and colon separators. The `aapt2` output should show
`com.aistudio.xtremeplayer.vkmz` as the package name and no
`application-debuggable` entry.
