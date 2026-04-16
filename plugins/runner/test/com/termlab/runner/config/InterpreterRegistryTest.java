package com.termlab.runner.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InterpreterRegistryTest {

    @Test
    void python_resolves() {
        assertEquals("python3", InterpreterRegistry.interpreterFor("py"));
    }

    @Test
    void shell_resolves() {
        assertEquals("bash", InterpreterRegistry.interpreterFor("sh"));
    }

    @Test
    void javascript_resolves() {
        assertEquals("node", InterpreterRegistry.interpreterFor("js"));
    }

    @Test
    void unknown_returnsNull() {
        assertNull(InterpreterRegistry.interpreterFor("xyz"));
    }

    @Test
    void extractExtension_fromFilename() {
        assertEquals("py", InterpreterRegistry.extractExtension("script.py"));
        assertEquals("sh", InterpreterRegistry.extractExtension("deploy.sh"));
        assertNull(InterpreterRegistry.extractExtension("Makefile"));
    }

    @Test
    void interpreterForFile_combinesLookup() {
        assertEquals("python3", InterpreterRegistry.interpreterForFile("test.py"));
        assertEquals("bash", InterpreterRegistry.interpreterForFile("run.sh"));
        assertNull(InterpreterRegistry.interpreterForFile("Makefile"));
    }
}
