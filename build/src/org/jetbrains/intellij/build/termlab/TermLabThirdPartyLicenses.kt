package org.jetbrains.intellij.build.termlab

import org.jetbrains.intellij.build.LibraryLicense

internal object TermLabThirdPartyLicenses {
  val LICENSES: List<LibraryLicense> = listOf(
    LibraryLicense(
      name = "Apache MINA SSHD",
      libraryName = "apache.sshd.osgi",
      url = "https://mina.apache.org/sshd-project/",
    )
      .apache("https://github.com/apache/mina-sshd/blob/master/LICENSE.txt")
      .suppliedByOrganizations("The Apache Software Foundation")
      .copyrightText("Copyright 2008-2026 The Apache Software Foundation"),

    LibraryLicense(
      name = "Apache MINA SSHD SFTP",
      libraryName = "sshd-sftp",
      version = "2.15.0",
      url = "https://mina.apache.org/sshd-project/",
    )
      .apache("https://github.com/apache/mina-sshd/blob/master/LICENSE.txt")
      .suppliedByOrganizations("The Apache Software Foundation")
      .copyrightText("Copyright 2008-2026 The Apache Software Foundation"),

    LibraryLicense(
      name = "Git for Windows (Git Bash)",
      attachedTo = "intellij.termlab.core",
      version = TermLabBundledGitForWindows.RELEASE_TAG.removePrefix("v"),
      url = "https://gitforwindows.org/",
      license = "GPL 2.0 and bundled third-party notices",
      licenseUrl = "https://github.com/git-for-windows/git/blob/main/COPYING",
      spdxIdentifier = "GPL-2.0-only",
      supplier = "Organization: Git for Windows contributors",
      copyrightText = "Copyright The Git for Windows contributors and upstream projects",
    ),
  )
}
