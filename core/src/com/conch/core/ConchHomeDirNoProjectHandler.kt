// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.conch.core

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ex.NoProjectStateHandler
import java.nio.file.Path

/**
 * Skips the platform welcome frame entirely and opens the user's home
 * directory as the default Conch workspace. Registered via the
 * `com.intellij.noProjectStateHandler` extension point.
 *
 * The platform (`IdeStarter.openProjectIfNeeded`) consults this EP when it
 * has decided it has no project to open and would otherwise fall through
 * to `WelcomeFrame.showWelcomeFrame`. Returning a non-null handler
 * short-circuits that branch — the platform invokes our handler instead
 * of preparing the welcome frame.
 *
 * Conch is a terminal workstation: there is no "project" to pick in any
 * meaningful sense. The terminal tool window is the entire UI, and it
 * needs a base directory to launch shells from. The user's home directory
 * is the right default across Mac, Linux, and Windows.
 */
internal class ConchHomeDirNoProjectHandler : NoProjectStateHandler {
  override fun createHandler(): (suspend () -> Project?) = {
    val home = Path.of(System.getProperty("user.home"))
    LOG.info("Conch: opening default workspace at $home")
    ProjectUtil.openOrImportAsync(home, OpenProjectTask())
  }

  private companion object {
    private val LOG = logger<ConchHomeDirNoProjectHandler>()
  }
}
