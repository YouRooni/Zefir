# Releasing

[Documentation](README.md) / Development

The [release workflow](../.github/workflows/release.yml) runs on pushed version tags. Branch pushes and pull requests do not trigger it. There is no scheduled or manual build trigger.

## Signing setup

Enable GitHub Actions and configure these repository secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded release keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing alias |
| `ANDROID_KEY_PASSWORD` | Key password |

Keep the existing release key so users can update installed APKs. Never commit it. With local `keystore.properties` configured, the following script uploads the four values through an authenticated GitHub CLI session without printing them:

```powershell
pwsh -File scripts/setup-github-signing.ps1
```

Only the publishing job receives `contents: write`; it uses GitHub's built-in token. The core builds on a hosted Linux runner, Windows packages build on a hosted Windows runner and Linux packages on a hosted Linux runner.

## Prepare a release

1. Set `versionName` and increment `versionCode` in [version.properties](../version.properties).
2. Write factual release notes in `docs/releases/<versionName>.md`.
3. Run `./gradlew :shared:desktopTest` and `python scripts/release-metadata.py v<versionName>`.
4. Check that documentation describes the packages the workflow actually produces.
5. Commit the release changes and push the matching tag.

For example, with `versionName=1.2.0-beta.1`:

```bash
git add version.properties docs/releases/1.2.0-beta.1.md
git commit -m "Release 1.2.0-beta.1"
git push origin main
git tag -a v1.2.0-beta.1 -m "1.2.0-beta.1"
git push origin v1.2.0-beta.1
```

Tags must match `versionName`. Supported forms include `v1.2.0`, `v1.2.0-beta`, `v1.2.0-beta.1`, `v1.2.0-alpha.1` and `v1.2.0-rc.1`. Prerelease tags do not replace the latest stable release.

For the first published build, update the availability text in all three READMEs and [the user guide](USAGE.md).

## Published assets

The workflow validates metadata and signing credentials, builds the core, checks signed APKs, runs desktop tests and packages Windows and Linux. It publishes a draft only after the platform jobs pass.

| Asset | Contents |
| --- | --- |
| `Zefir-<version>-android-<abi>.apk` | One signed APK per ABI configured in the workflow |
| `Zefir-<version>-windows-x64-portable.zip` | Windows app, Java runtime and native resources |
| `Zefir-<version>-linux-x64.tar.gz` | Linux app, Java runtime and native resources |
| `Zefir-<version>-source.tar.gz` | Repository sources plus the patched core and mihomo sources |
| `LICENSE`, `NOTICE` | License and third-party attribution |
| `SHA256SUMS.txt` | Checksums for the published assets |

The current workflow uploads the portable Windows ZIP, not the custom setup executable. Building an installer locally does not attach it to a release.

Windows binaries are unsigned. APK signatures are verified during the build. Keep the complete source archive available alongside the binaries.

## Failed releases

If a run fails before creating a draft, fix the cause and rerun the failed jobs. If uploading or publishing fails after draft creation, inspect and finish that draft before retrying; the workflow refuses to overwrite an existing release.

For changed code, use a new version and tag. Do not replace the binaries of a published version.
