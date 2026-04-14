package com.conch.editor.scratch;

import com.intellij.openapi.util.Key;

/** Marker key used to distinguish scratch LightVirtualFiles from other LightVirtualFiles. */
public final class ScratchMarker {

    public static final Key<Boolean> KEY = Key.create("conch.editor.scratchMarker");

    private ScratchMarker() {}
}
