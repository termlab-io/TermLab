package com.termlab.share.conversion;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record SshConfigEntry(
    @NotNull String alias,
    @Nullable String hostName,
    int port,
    @Nullable String user,
    @Nullable String identityFile,
    @Nullable String proxyCommand,
    @Nullable String proxyJump,
    @NotNull List<String> warnings
) {}
