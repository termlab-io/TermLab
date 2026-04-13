package com.conch.core;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Strips IDE refactoring actions at startup.
 *
 * <p>Conch isn't an IDE — it's a terminal workstation — and the platform's
 * refactoring menu drags along a Ctrl+T "Refactor This" popup plus an entire
 * top-level "Refactor" menu that's meaningless in this context. Rather than
 * rebinding Ctrl+T away from the refactoring popup (which still leaves the
 * menu entries and still reserves the shortcut), we unregister the actions
 * outright. Unregistration removes them from every keymap, every menu, and
 * every popup context in one step.
 *
 * <p>This is pure UI surgery: the refactoring implementation classes stay on
 * disk untouched, so nothing else in the platform breaks. Same approach as
 * {@link ConchToolWindowCustomizer} takes for unwanted tool windows.
 *
 * <p>Action ids sourced from
 * {@code platform/platform-resources/src/idea/LangActions.xml} (the
 * {@code RefactoringMenu} group definition). If upstream adds new refactoring
 * actions we haven't listed here, they'll still appear — add them below.
 */
public final class ConchRefactoringStripper implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(ConchRefactoringStripper.class);

    /**
     * Action / group ids to unregister. Order doesn't matter, but grouping
     * them by purpose keeps the list scannable. The top-level group and the
     * quick-popup action are the important ones — the individual entries
     * just keep stray menu/popup references clean.
     */
    private static final List<String> UNWANTED_ACTION_IDS = List.of(
        // Top-level "Refactor" menu and the Ctrl+T quick popup.
        "RefactoringMenu",
        "IntroduceActionsGroup",
        "Refactorings.QuickListPopupAction",

        // Core rename / signature / move / delete.
        "RenameElement",
        "ChangeSignature",
        "Move",
        "SafeDelete",
        "Inline",

        // Introduce-* family.
        "IntroduceVariable",
        "IntroduceConstant",
        "IntroduceField",
        "IntroduceParameter",
        "IntroduceParameterObject",

        // Extract-* family.
        "ExtractMethod",
        "ExtractClass",
        "ExtractInclude",
        "ExtractInterface",
        "ExtractSuperclass",
        "ExtractModule",

        // Misc.
        "MembersPullUp",
        "MemberPushDown",
        "InvertBoolean"
    );

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        ActionManager actionManager = ActionManager.getInstance();
        int removed = 0;
        for (String id : UNWANTED_ACTION_IDS) {
            if (actionManager.getAction(id) == null) continue;
            actionManager.unregisterAction(id);
            removed++;
            LOG.info("Conch: unregistered refactoring action '" + id + "'");
        }
        LOG.info("Conch: stripped " + removed + " refactoring action(s) / group(s)");
    }
}
