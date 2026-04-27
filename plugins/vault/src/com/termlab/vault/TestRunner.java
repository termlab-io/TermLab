package com.termlab.vault;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

/**
 * Standalone test runner for the vault plugin's Phase 1 unit tests. Runs
 * the full {@code com.termlab.vault} test tree via the JUnit 5 platform
 * launcher and prints a human-readable summary.
 *
 * <p>Usage:
 * <pre>
 *   bash bazel.cmd run //termlab/plugins/vault:vault_test_runner
 * </pre>
 *
 * <p>This exists because the intellij-community Bazel tree doesn't ship
 * junit-platform-console-launcher, and the full {@code jps_test} machinery
 * is overkill for pure crypto unit tests with no platform dependencies.
 */
public final class TestRunner {

    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectPackage("com.termlab.vault"))
            .build();

        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        Launcher launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);

        TestExecutionSummary summary = listener.getSummary();
        PrintWriter writer = new PrintWriter(System.out, true);
        summary.printTo(writer);

        if (!summary.getFailures().isEmpty()) {
            summary.printFailuresTo(writer);
            System.exit(1);
        }
        System.exit(0);
    }
}
