package com.conch.editor.remote;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.application.ApplicationManager;

/**
 * On IDE shutdown, clear the temp root entirely. Anything
 * important is either saved-back to remote or explicitly
 * discarded by the user.
 */
public final class RemoteEditorShutdownListener implements AppLifecycleListener {

    @Override
    public void appWillBeClosed(boolean isRestart) {
        RemoteEditService service = ApplicationManager.getApplication()
            .getService(RemoteEditService.class);
        if (service == null) return;
        RemoteEditorCleanup.purgeRoot(service.tempRoot());
        RemoteFileBindingRegistry registry = ApplicationManager.getApplication()
            .getService(RemoteFileBindingRegistry.class);
        if (registry != null) registry.clear();
    }

    // appClosing override point — unused, but listed here in case
    // a future platform contract requires it.
    @Override
    public void appClosing() {}
}
