package com.termlab.share.planner;

import com.termlab.share.model.ShareBundle;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public record ExportPlan(
    @NotNull ShareBundle bundle,
    @NotNull List<ConversionWarning> warnings,
    @NotNull List<String> autoPulledHostLabels,
    @NotNull List<String> convertedSshConfigAliases,
    @NotNull List<String> convertedKeyFilePaths
) {}
