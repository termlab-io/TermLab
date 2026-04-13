package com.conch.sftp.toolwindow;

import org.jetbrains.annotations.NotNull;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;

/**
 * Intra-table {@link Transferable} carrying the model-row indices
 * of the rows being dragged. Scoped by a table-identity token so a
 * handler can reject drops originating in a different pane.
 */
public final class FileRowsTransferable implements Transferable {

    public static final DataFlavor FLAVOR = new DataFlavor(
        DataFlavor.javaJVMLocalObjectMimeType + ";class=" + FileRowsTransferable.class.getName(),
        "Conch SFTP rows");

    private final Object sourceToken;
    private final int[] modelRows;

    public FileRowsTransferable(@NotNull Object sourceToken, int @NotNull [] modelRows) {
        this.sourceToken = sourceToken;
        this.modelRows = modelRows.clone();
    }

    public @NotNull Object sourceToken() {
        return sourceToken;
    }

    public int @NotNull [] modelRows() {
        return modelRows.clone();
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return new DataFlavor[]{FLAVOR};
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return FLAVOR.equals(flavor);
    }

    @Override
    public @NotNull Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (!FLAVOR.equals(flavor)) throw new UnsupportedFlavorException(flavor);
        return this;
    }
}
