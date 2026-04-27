package com.termlab.proxmox;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

public final class TestRunner {
    private TestRunner() {}

    public static void main(String[] args) {
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectPackage("com.termlab.proxmox"))
            .build();
        LauncherFactory.create().execute(request, listener);
        listener.getSummary().printTo(new java.io.PrintWriter(System.out));
        listener.getSummary().getFailures().forEach(failure -> {
            System.out.println(failure.getTestIdentifier().getDisplayName());
            failure.getException().printStackTrace(System.out);
        });
        if (listener.getSummary().getTotalFailureCount() > 0) {
            System.exit(1);
        }
    }
}
