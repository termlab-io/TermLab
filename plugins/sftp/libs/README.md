# Vendored libraries — Conch SFTP plugin

## sshd-sftp-2.15.0.jar

Apache MINA SSHD SFTP subsystem client/server. Provides
`org.apache.sshd.sftp.client.SftpClient`, `SftpClientFactory`, and the
directory entry / file attribute types used by this plugin.

- **Source:** https://repo1.maven.org/maven2/org/apache/sshd/sshd-sftp/2.15.0/sshd-sftp-2.15.0.jar
- **Version:** 2.15.0 (matches `intellij.libraries.sshd.osgi` used by
  the Conch SSH plugin — keep in lock-step on version bumps).
- **SHA-1:** `2e226055ed060c64ed76256a9c45de6d0109eef8`
- **License:** Apache License 2.0.

### Why vendored

This jar is committed to the repo rather than pulled through
`intellij-community`'s auto-generated `lib/MODULE.bazel` system so the
Conch SFTP plugin stays fully self-contained. The `maven libs` section
of that file is regenerated periodically, which would wipe out any
addition we made there.

### Updating

1. Pick the new version that matches `intellij.libraries.sshd.osgi`.
2. Download from the same Maven Central path with the new version.
3. Verify SHA-1 against Maven Central's `.sha1` sibling file.
4. Replace `sshd-sftp-<version>.jar` in this directory.
5. Update `plugins/sftp/BUILD.bazel` `java_import.jars`.
6. Update this README's version + SHA line.
