package com.conch.editor.remote;

import com.intellij.openapi.components.Service;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application-level registry mapping local temp paths to the
 * {@link RemoteFileBinding} describing their remote source. Owned
 * by the editor plugin and consulted from the save hook and
 * cleanup hooks.
 */
@Service(Service.Level.APP)
public final class RemoteFileBindingRegistry {

    private final Map<String, RemoteFileBinding> bindings = new ConcurrentHashMap<>();

    public void register(@NotNull RemoteFileBinding binding) {
        bindings.put(binding.tempPath().toAbsolutePath().toString(), binding);
    }

    public @Nullable RemoteFileBinding get(@NotNull String tempPathAbsolute) {
        return bindings.get(tempPathAbsolute);
    }

    public @Nullable RemoteFileBinding remove(@NotNull String tempPathAbsolute) {
        return bindings.remove(tempPathAbsolute);
    }

    public @NotNull Collection<RemoteFileBinding> all() {
        return bindings.values();
    }

    public int size() {
        return bindings.size();
    }

    public void clear() {
        bindings.clear();
    }
}
