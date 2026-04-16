package com.termlab.ssh;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

/**
 * Standalone test runner for the ssh plugin. Runs the full
 * {@code com.termlab.ssh} test tree via the JUnit 5 platform launcher and
 * prints a human-readable summary. Same shape as the vault plugin's
 * {@code TestRunner} — the intellij-community Bazel tree doesn't ship
 * junit-platform-console-launcher and jps_test is overkill for pure unit
 * tests with no IntelliJ application context.
 *
 * <p>Usage:
 * <pre>
 *   bash bazel.cmd run //termlab/plugins/ssh:ssh_test_runner
 * </pre>
 */
public final class TestRunner {

    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectPackage("com.termlab.ssh"))
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
