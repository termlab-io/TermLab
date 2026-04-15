package com.conch.sftp.filepicker;

import com.conch.core.filepicker.FileSource;
import com.conch.core.filepicker.FileSourceProvider;
import com.conch.ssh.model.HostStore;
import com.conch.ssh.model.SshHost;
import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers one {@link SftpFileSource} per configured {@link SshHost}
 * with the unified file picker. Queries {@link HostStore} fresh on
 * each call so newly-added hosts appear in the next opened picker
 * without requiring a restart.
 */
public final class SftpFileSourceProvider implements FileSourceProvider {

    @Override
    public @NotNull List<FileSource> listSources() {
        HostStore store = ApplicationManager.getApplication().getService(HostStore.class);
        if (store == null) return List.of();
        List<FileSource> sources = new ArrayList<>();
        for (SshHost host : store.getHosts()) {
            sources.add(new SftpFileSource(host));
        }
        return sources;
    }
}
