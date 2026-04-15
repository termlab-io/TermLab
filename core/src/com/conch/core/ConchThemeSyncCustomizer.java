package com.conch.core;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ensures OS theme sync uses Conch's own light/dark themes instead of
 * IntelliJ defaults, and keeps first-launch default on TermLab Light.
 */
public final class ConchThemeSyncCustomizer implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(ConchThemeSyncCustomizer.class);

    private static final String LIGHT_THEME_NAME = "TermLab Light";
    private static final String DARK_THEME_NAME = "TermLab Dark";
    private static final Set<String> BUILTIN_LAF_NAMES = Set.of(
        "Darcula",
        "IntelliJ Light",
        "Light"
    );

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        try {
            Class<?> lafManagerClass = Class.forName("com.intellij.ide.ui.LafManager");
            Object lafManager = lafManagerClass.getMethod("getInstance").invoke(null);
            if (lafManager == null) return;

            List<Object> installed = getInstalledLookAndFeels(lafManager);
            removeBuiltinLookAndFeels(lafManager, installed);
            pruneBuiltinFromLafManagerState(lafManager);
            installed = getInstalledLookAndFeels(lafManager);

            Object light = findThemeByName(installed, LIGHT_THEME_NAME);
            Object dark = findThemeByName(installed, DARK_THEME_NAME);
            if (light == null || dark == null) {
                LOG.warn("Conch: could not find TermLab themes in installed LAF list");
                return;
            }

            // Wire "Sync with OS" preferred light/dark targets to TermLab themes.
            setPreferredThemeTargets(lafManager, light, dark);

            // Keep first-launch/default behavior on TermLab Light:
            // if we're still on built-in LAFs, switch to our light theme.
            Object current = tryInvokeNoArgs(lafManager, "getCurrentLookAndFeel");
            String currentName = getLafName(current);
            if (currentName == null || BUILTIN_LAF_NAMES.contains(currentName)) {
                if (setCurrentLookAndFeel(lafManager, light)) {
                    tryInvokeNoArgs(lafManager, "updateUI");
                    notifyLafListOrStateChanged(lafManager);
                }
            }
        } catch (Throwable t) {
            LOG.warn("Conch: failed to configure theme sync preferences", t);
        }
    }

    private static void removeBuiltinLookAndFeels(Object lafManager, List<Object> installed) {
        List<Object> filtered = new ArrayList<>();
        boolean removedAny = false;
        for (Object info : installed) {
            String name = getLafName(info);
            if (isBuiltinThemeName(name)) {
                removedAny = true;
                continue;
            }
            filtered.add(info);
        }
        if (!removedAny) return;

        if (setInstalledLookAndFeels(lafManager, filtered)) {
            LOG.info("Conch: removed built-in IntelliJ Light / Darcula themes from installed LAFs");
            return;
        }

        // Fallback: try mutating the returned collection if implementation is live/mutable.
        Object raw = tryInvokeNoArgs(lafManager, "getInstalledLookAndFeels");
        if (!(raw instanceof Iterable<?> iterable)) return;
        try {
            @SuppressWarnings("unchecked")
            Iterable<Object> mutable = (Iterable<Object>) iterable;
            Iterator<Object> it = mutable.iterator();
            while (it.hasNext()) {
                Object info = it.next();
                if (isBuiltinThemeName(getLafName(info))) {
                    it.remove();
                }
            }
            LOG.info("Conch: removed built-in IntelliJ Light / Darcula themes via mutable iterable fallback");
        } catch (Throwable ignored) {
            // Non-mutable or copy-backed iterable — keep runtime remap fallback.
        }
    }

    private static boolean setInstalledLookAndFeels(Object lafManager, List<Object> filtered) {
        String[] candidateNames = {
            "setInstalledLookAndFeels",
            "setInstalledLaFs",
            "setInstalledLafs",
        };
        for (String methodName : candidateNames) {
            Method[] methods = lafManager.getClass().getMethods();
            for (Method method : methods) {
                if (!method.getName().equals(methodName)) continue;
                if (method.getParameterCount() != 1) continue;
                Class<?> paramType = method.getParameterTypes()[0];
                try {
                    Object arg = adaptCollectionArgument(paramType, filtered);
                    if (arg == null) continue;
                    method.invoke(lafManager, arg);
                    return true;
                } catch (Throwable ignored) {
                    // Try next overload.
                }
            }
        }
        return false;
    }

    private static Object adaptCollectionArgument(Class<?> paramType, List<Object> filtered) {
        if (paramType.isArray()) {
            Class<?> component = paramType.getComponentType();
            Object arr = Array.newInstance(component, filtered.size());
            for (int i = 0; i < filtered.size(); i++) {
                Object item = filtered.get(i);
                if (item != null && !component.isAssignableFrom(item.getClass())) {
                    return null;
                }
                Array.set(arr, i, item);
            }
            return arr;
        }
        if (paramType.isAssignableFrom(ArrayList.class) || paramType.isAssignableFrom(List.class)) {
            return filtered;
        }
        if (paramType.isAssignableFrom(Iterable.class)) {
            return filtered;
        }
        return null;
    }

    private static void pruneBuiltinFromLafManagerState(Object lafManager) {
        int removed = 0;
        Class<?> klass = lafManager.getClass();
        while (klass != null && klass != Object.class) {
            for (Field field : klass.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(lafManager);
                    if (value == null) continue;
                    removed += pruneValue(field, lafManager, value);
                } catch (Throwable ignored) {
                    // Best-effort reflection only.
                }
            }
            klass = klass.getSuperclass();
        }
        if (removed > 0) {
            LOG.info("Conch: pruned " + removed + " built-in LAF entries from LafManager state");
            notifyLafListOrStateChanged(lafManager);
        }
    }

    private static int pruneValue(Field field, Object owner, Object value) throws IllegalAccessException {
        int removed = 0;
        if (value instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<Object> mutable = (List<Object>) list;
            removed += removeBuiltinFromCollection(mutable);
            return removed;
        }
        if (value instanceof Set<?> set) {
            @SuppressWarnings("unchecked")
            Set<Object> mutable = (Set<Object>) set;
            List<Object> toRemove = new ArrayList<>();
            for (Object item : mutable) {
                if (isBuiltinItem(item)) toRemove.add(item);
            }
            for (Object item : toRemove) {
                if (mutable.remove(item)) removed++;
            }
            return removed;
        }
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> mutable = (Map<Object, Object>) map;
            List<Object> keysToRemove = new ArrayList<>();
            for (Map.Entry<Object, Object> entry : mutable.entrySet()) {
                if (isBuiltinItem(entry.getKey()) || isBuiltinItem(entry.getValue())) {
                    keysToRemove.add(entry.getKey());
                }
            }
            for (Object key : keysToRemove) {
                if (mutable.remove(key) != null) removed++;
            }
            return removed;
        }
        if (value.getClass().isArray()) {
            int len = Array.getLength(value);
            List<Object> kept = new ArrayList<>();
            for (int i = 0; i < len; i++) {
                Object item = Array.get(value, i);
                if (isBuiltinItem(item)) {
                    removed++;
                } else {
                    kept.add(item);
                }
            }
            if (removed > 0) {
                Object arr = Array.newInstance(value.getClass().getComponentType(), kept.size());
                for (int i = 0; i < kept.size(); i++) {
                    Array.set(arr, i, kept.get(i));
                }
                try {
                    field.set(owner, arr);
                } catch (Throwable ignored) {
                    // Non-writeable field; ignore.
                }
            }
            return removed;
        }
        return 0;
    }

    private static int removeBuiltinFromCollection(List<Object> list) {
        int removed = 0;
        Iterator<Object> it = list.iterator();
        while (it.hasNext()) {
            Object item = it.next();
            if (isBuiltinItem(item)) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    private static boolean isBuiltinItem(Object item) {
        if (item == null) return false;
        if (item instanceof String s) return isBuiltinThemeName(s);
        String name = getLafName(item);
        if (isBuiltinThemeName(name)) return true;
        String fallback = readNameByReflection(item);
        return isBuiltinThemeName(fallback);
    }

    private static String readNameByReflection(Object target) {
        try {
            Method getter = target.getClass().getMethod("getName");
            Object value = getter.invoke(target);
            if (value instanceof String s) return s;
        } catch (Throwable ignored) {
            // Fall through.
        }
        try {
            Field field = target.getClass().getDeclaredField("name");
            field.setAccessible(true);
            Object value = field.get(target);
            if (value instanceof String s) return s;
        } catch (Throwable ignored) {
            // Ignore.
        }
        return null;
    }

    private static void notifyLafListOrStateChanged(Object lafManager) {
        tryInvokeNoArgs(lafManager, "updateUI");
        tryInvokeNoArgs(lafManager, "fireLookAndFeelChanged");
        tryInvokeNoArgs(lafManager, "fireLafChanged");
        tryInvokeNoArgs(lafManager, "fireLookAndFeelListChanged");
        tryInvokeNoArgs(lafManager, "notifyLookAndFeelChanged");
    }

    private static boolean isBuiltinThemeName(String name) {
        return name != null && BUILTIN_LAF_NAMES.contains(name);
    }

    private static List<Object> getInstalledLookAndFeels(Object lafManager) {
        Object result = tryInvokeNoArgs(lafManager, "getInstalledLookAndFeels");
        List<Object> out = new ArrayList<>();
        if (result == null) return out;
        if (result.getClass().isArray()) {
            int len = Array.getLength(result);
            for (int i = 0; i < len; i++) {
                Object item = Array.get(result, i);
                if (item != null) out.add(item);
            }
            return out;
        }
        if (result instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null) out.add(item);
            }
        }
        return out;
    }

    private static Object findThemeByName(List<Object> installed, String name) {
        for (Object info : installed) {
            String infoName = getLafName(info);
            if (name.equals(infoName)) return info;
        }
        return null;
    }

    private static String getLafName(Object lafInfo) {
        if (lafInfo == null) return null;
        try {
            Method getName = lafInfo.getClass().getMethod("getName");
            Object name = getName.invoke(lafInfo);
            return name instanceof String ? (String) name : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void setPreferredThemeTargets(Object lafManager, Object light, Object dark) {
        Method[] methods = lafManager.getClass().getMethods();
        for (Method method : methods) {
            String n = method.getName().toLowerCase(Locale.ROOT);
            if (!n.startsWith("setpreferred")) continue;
            if (method.getParameterCount() != 1) continue;
            Class<?> p = method.getParameterTypes()[0];
            if (!p.isAssignableFrom(light.getClass()) && !p.isAssignableFrom(dark.getClass())) continue;
            try {
                if (n.contains("light")) {
                    method.invoke(lafManager, light);
                } else if (n.contains("dark")) {
                    method.invoke(lafManager, dark);
                }
            } catch (Throwable t) {
                LOG.warn("Conch: could not invoke " + method.getName(), t);
            }
        }
    }

    private static boolean setCurrentLookAndFeel(Object lafManager, Object lafInfo) {
        // Most versions use setCurrentLookAndFeel(info).
        if (tryInvoke(lafManager, "setCurrentLookAndFeel", lafInfo)) return true;
        // Some builds expose a two-arg overload with a lockEditorScheme flag.
        if (tryInvoke(lafManager, "setCurrentLookAndFeel", lafInfo, Boolean.FALSE)) return true;
        // Older variants may use setLookAndFeel(info).
        return tryInvoke(lafManager, "setLookAndFeel", lafInfo);
    }

    private static Object tryInvokeNoArgs(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean tryInvoke(Object target, String methodName, Object... args) {
        Method[] methods = target.getClass().getMethods();
        for (Method method : methods) {
            if (!method.getName().equals(methodName)) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != args.length) continue;
            if (!parametersMatch(params, args)) continue;
            try {
                method.invoke(target, args);
                return true;
            } catch (Throwable ignored) {
                // Try next overload.
            }
        }
        return false;
    }

    private static boolean parametersMatch(Class<?>[] params, Object[] args) {
        for (int i = 0; i < params.length; i++) {
            Object arg = args[i];
            Class<?> param = params[i];
            if (arg == null) continue;
            if (param.isPrimitive()) {
                if (param == boolean.class && arg instanceof Boolean) continue;
                if (param == int.class && arg instanceof Integer) continue;
                if (param == long.class && arg instanceof Long) continue;
                if (param == double.class && arg instanceof Double) continue;
                if (param == float.class && arg instanceof Float) continue;
                if (param == short.class && arg instanceof Short) continue;
                if (param == byte.class && arg instanceof Byte) continue;
                if (param == char.class && arg instanceof Character) continue;
                return false;
            }
            if (!param.isAssignableFrom(arg.getClass())) return false;
        }
        return true;
    }
}
