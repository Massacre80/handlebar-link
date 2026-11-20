# Releasing

A release is a signed tag (`vX.Y.Z`). The tag workflow builds the same APK a developer
builds locally, attaches it to a GitHub release together with its checksum and mapping
file, and never sees the identity or keystore in the repository: both are injected from
GitHub Actions secrets.

## One-time setup

1. **Release keystore.** Generate one and keep it outside the repository:

   ```sh
   keytool -genkeypair -keystore ~/.handlebarlink-release.jks -storetype JKS \
     -keyalg RSA -keysize 2048 -validity 10000 -alias handlebarlink \
     -dname "CN=Handlebar Link, O=Devsed-official, C=IN"
   ```

   Put `HL_KEYSTORE_PATH`, `HL_KEYSTORE_PASSWORD`, `HL_KEY_ALIAS` and `HL_KEY_PASSWORD`
   in `~/.gradle/gradle.properties` (or export them). Back the keystore up: losing it
   means you can never update an installed release again.

2. **CarPlay identity secret.** Base64-encode the directory holding `identity.pk8` and
   `certificate.p7b` and store it as the repository secret `HL_MFI_B64`:

   ```sh
   tar -czf - -C ~/.carlink-private offline-mfi | base64 > /tmp/mfi.b64
   ```

   The workflow expects `HL_MFI_B64` to decode to a tar.gz containing an `offline-mfi/`
   directory. Publishing the APK makes the bundled private key extractable; this is the
   same trade-off CarLink documents and is why the README carries a CarPlay notice.
   Never commit these files (`*.pk8`, `*.p7b` are gitignored).

3. **Signing secrets.** Add repository secrets `HL_KEYSTORE_B64` (base64 of the JKS),
   `HL_KEYSTORE_PASSWORD`, `HL_KEY_ALIAS` and `HL_KEY_PASSWORD`.

## Release steps

1. Be on `main`, clean, with `origin/main` up to date. Do not start a release from a
   feature worktree.
2. Set `versionName`/`versionCode` in `app/build.gradle.kts` and add the section to
   `CHANGELOG.md`. Never reuse a `versionCode`.
3. Run the gate and the build as one command — never pipe `check` into `&&`
   (docs/LESSONS.md 39, 98):

   ```sh
   ./gradlew check > /tmp/hl-check.log 2>&1 && ./gradlew assembleRelease
   ```

4. Run `scripts/release.sh <version>` to verify signing, identity assets and checksums.
   It writes `handlebar-link-<version>.apk` and its `.sha256` file next to the build output.
5. Commit (`./gradlew check && git commit`), tag `v<version>` and push the branch and tag:

   ```sh
   git tag -a v2.0.0 -m "Handlebar Link 2.0.0"
   git push origin main && git push origin v2.0.0
   ```

6. The `release.yml` workflow rebuilds from the tag, verifies the APK and attaches the
   APK, checksum and `mapping.txt` to a GitHub release. Edit the generated release notes
   to include the SHA-256, the signing certificate fingerprint and the CarPlay notice.
7. Download the published APK once, re-check its SHA-256 and install it on the Poco.
   If the identity was missing in the tag build, delete and redo the release: such an APK
   cannot start CarPlay.

## After a release

- Keep `mapping.txt` from the release asset for crash reports.
- Update `docs/PLAN.md` status if a milestone closed with this release.
- If the signing key is rotated, update the fingerprint in the README and tell riders
  they must uninstall first.
