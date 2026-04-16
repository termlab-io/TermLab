package com.termlab.share.planner;

import com.termlab.share.model.ImportItem;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public record ImportPlan(@NotNull List<ImportItem> items) {
    public int countByStatus(@NotNull ImportItem.Status status) {
        return (int) items.stream().filter(i -> i.status == status).count();
    }
}
