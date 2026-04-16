package com.termlab.runner.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Shared Gson configuration for the runner plugin.
 */
public final class RunnerGson {

    public static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .create();

    private RunnerGson() {}
}
