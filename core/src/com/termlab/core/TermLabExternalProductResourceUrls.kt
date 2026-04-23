package com.termlab.core

import com.intellij.platform.ide.customization.ExternalProductResourceUrls
import com.intellij.util.Url
import com.intellij.util.Urls

/**
 * Tells the platform's UpdateChecker where to fetch the updates.xml
 * metadata for TermLab. The default points at a localhost server intended
 * for dev / end-to-end testing; override at launch with
 * `-Dtermlab.update.url=<URL>` to aim at a different host (a real GitHub
 * release asset, a staging server, etc.).
 *
 * Once the GitHub release pipeline is ready to be the source of truth,
 * change DEFAULT_UPDATE_URL to the release-asset URL
 * (https://github.com/termlab-io/TermLab/releases/latest/download/updates.xml)
 * — no other plumbing needs to move.
 */
class TermLabExternalProductResourceUrls : ExternalProductResourceUrls {
  override val updateMetadataUrl: Url
    get() = Urls.newFromEncoded(
      System.getProperty("termlab.update.url", DEFAULT_UPDATE_URL)
    )

  private companion object {
    const val DEFAULT_UPDATE_URL = "https://github.com/termlab-io/TermLab/releases/latest/download/updates.xml"
  }
}
