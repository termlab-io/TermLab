package com.termlab.core

import com.intellij.ide.GeneralSettings
import com.intellij.ide.util.TipAndTrickBean
import com.intellij.ide.util.TipAndTrickManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.wm.ToolWindowManager
import java.util.concurrent.ThreadLocalRandom

class TermLabTipsStartupActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    val application = ApplicationManager.getApplication()
    if (application.isHeadlessEnvironment || application.isUnitTestMode) {
      return
    }

    val settings = GeneralSettings.getInstance()
    if (!settings.isShowTipsOnStartup) {
      LOG.info("TermLab tips skipped: show tips on startup is disabled")
      return
    }

    val tips = TipAndTrickBean.EP_NAME.extensionList
    if (tips.isEmpty()) {
      LOG.warn("TermLab tips skipped: no tipAndTrick extensions are registered")
      return
    }
    val tip = pickStartupTip(tips)

    val tipManager = TipAndTrickManager.getInstance()
    ToolWindowManager.getInstance(project).invokeLater {
      application.executeOnPooledThread {
        LOG.info("Showing TermLab Tip of the Day dialog: ${tip.id}")
        kotlinx.coroutines.runBlocking {
          tipManager.showTipDialog(project, tip)
        }
      }
    }
  }

  private companion object {
    private const val LAST_STARTUP_TIP_ID = "termlab.tips.last.startup.tip.id"
    private val LOG = Logger.getInstance(TermLabTipsStartupActivity::class.java)

    private fun pickStartupTip(allTips: List<TipAndTrickBean>): TipAndTrickBean {
      val tips = allTips
        .filter { it.fileName?.startsWith("TermLab") == true }
        .ifEmpty { allTips }

      if (tips.size == 1) {
        rememberStartupTip(tips[0])
        return tips[0]
      }

      val properties = PropertiesComponent.getInstance()
      val lastTipId = properties.getValue(LAST_STARTUP_TIP_ID)
      val candidates = tips.filter { it.id != lastTipId }.ifEmpty { tips }
      val picked = candidates[ThreadLocalRandom.current().nextInt(candidates.size)]
      rememberStartupTip(picked)
      return picked
    }

    private fun rememberStartupTip(tip: TipAndTrickBean) {
      PropertiesComponent.getInstance().setValue(LAST_STARTUP_TIP_ID, tip.id)
    }
  }
}
