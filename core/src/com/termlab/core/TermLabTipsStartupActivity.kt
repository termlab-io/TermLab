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
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Window
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
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
        installShowTipsOnStartupCheckBox()
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

    private fun installShowTipsOnStartupCheckBox() {
      SwingUtilities.invokeLater {
        val timer = Timer(100, null)
        timer.addActionListener(object : java.awt.event.ActionListener {
          private var attempts = 0

          override fun actionPerformed(event: java.awt.event.ActionEvent) {
            attempts++
            if (installShowTipsOnStartupCheckBoxNow() || attempts >= 30) {
              timer.stop()
            }
          }
        })
        timer.isRepeats = true
        timer.start()
      }
    }

    private fun installShowTipsOnStartupCheckBoxNow(): Boolean {
      val dialog = Window.getWindows()
        .filterIsInstance<JDialog>()
        .firstOrNull { it.isShowing && it.title == "Tip of the Day" }
        ?: return false

      if (findComponent(dialog.rootPane) { it is JCheckBox && it.text == "Show tips on startup" } != null) {
        return true
      }

      val closeButton = findComponent(dialog.rootPane) { it is JButton && it.text == "Close" } ?: return false
      val buttonPanel = closeButton.parent ?: return false
      val settings = GeneralSettings.getInstance()
      val checkBox = JCheckBox("Show tips on startup", settings.isShowTipsOnStartup)
      checkBox.addActionListener {
        settings.isShowTipsOnStartup = checkBox.isSelected
      }

      val southPanel = findAncestor(buttonPanel) { panel ->
        panel.layout is BorderLayout && SwingUtilities.isDescendingFrom(buttonPanel, panel)
      } ?: buttonPanel

      if (southPanel.layout is BorderLayout) {
        val leftPanel = JPanel()
        leftPanel.isOpaque = false
        leftPanel.add(checkBox)
        southPanel.add(leftPanel, BorderLayout.WEST)
      }
      else {
        buttonPanel.add(checkBox, 0)
      }

      southPanel.revalidate()
      southPanel.repaint()
      dialog.pack()
      return true
    }

    private fun findAncestor(component: Component, predicate: (Container) -> Boolean): Container? {
      var parent = component.parent
      while (parent != null) {
        if (predicate(parent)) {
          return parent
        }
        parent = parent.parent
      }
      return null
    }

    private fun findComponent(root: Component, predicate: (Component) -> Boolean): Component? {
      if (predicate(root)) {
        return root
      }
      if (root is Container) {
        for (child in root.components) {
          val match = findComponent(child, predicate)
          if (match != null) {
            return match
          }
        }
      }
      return null
    }
  }
}
