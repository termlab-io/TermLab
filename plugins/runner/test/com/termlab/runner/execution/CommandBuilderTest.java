package com.termlab.runner.execution;

import com.termlab.runner.config.RunConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandBuilderTest {

    @Test
    void localCommand_simpleScript() {
        RunConfig config = RunConfig.create("Test", null, "python3", List.of(), null, Map.of(), List.of());
        List<String> command = CommandBuilder.buildLocalCommand(config, "/home/user/test.py");
        assertEquals(List.of("python3", "/home/user/test.py"), command);
    }

    @Test
    void localCommand_withInterpreterArgs() {
        RunConfig config = RunConfig.create("Test", null, "python3", List.of("-u", "-O"), null, Map.of(), List.of());
        List<String> command = CommandBuilder.buildLocalCommand(config, "/test.py");
        assertEquals(List.of("python3", "-u", "-O", "/test.py"), command);
    }

    @Test
    void localCommand_withScriptArgs() {
        RunConfig config = RunConfig.create("Test", null, "bash", List.of(), null, Map.of(), List.of("--verbose", "input.csv"));
        List<String> command = CommandBuilder.buildLocalCommand(config, "/run.sh");
        assertEquals(List.of("bash", "/run.sh", "--verbose", "input.csv"), command);
    }

    @Test
    void localCommand_withBothArgs() {
        RunConfig config = RunConfig.create("Test", null, "python3", List.of("-u"), null, Map.of(), List.of("--dry-run"));
        List<String> command = CommandBuilder.buildLocalCommand(config, "/script.py");
        assertEquals(List.of("python3", "-u", "/script.py", "--dry-run"), command);
    }

    @Test
    void remoteCommand_simpleScript() {
        RunConfig config = RunConfig.create("Test", null, "python3", List.of(), null, Map.of(), List.of());
        String command = CommandBuilder.buildRemoteCommand(config, "/home/user/test.py");
        assertEquals("python3 /home/user/test.py", command);
    }

    @Test
    void remoteCommand_withWorkingDirectory() {
        RunConfig config = RunConfig.create("Test", null, "bash", List.of(), "/opt/app", Map.of(), List.of());
        String command = CommandBuilder.buildRemoteCommand(config, "/opt/app/run.sh");
        assertEquals("cd /opt/app && bash /opt/app/run.sh", command);
    }

    @Test
    void remoteCommand_withEnvVars() {
        RunConfig config = RunConfig.create("Test", null, "python3", List.of(), null, Map.of("DEBUG", "1", "PORT", "8080"), List.of());
        String command = CommandBuilder.buildRemoteCommand(config, "/test.py");
        assertTrue(command.contains("DEBUG=1"));
        assertTrue(command.contains("PORT=8080"));
        assertTrue(command.endsWith("python3 /test.py"));
    }

    @Test
    void remoteCommand_withEverything() {
        RunConfig config = RunConfig.create("Test", null, "python3", List.of("-u"), "/home/deploy", Map.of("ENV", "prod"), List.of("--run"));
        String command = CommandBuilder.buildRemoteCommand(config, "/home/deploy/app.py");
        assertEquals("cd /home/deploy && ENV=prod python3 -u /home/deploy/app.py --run", command);
    }

    @Test
    void remoteCommand_shellEscapesSpacesInPath() {
        RunConfig config = RunConfig.create("Test", null, "bash", List.of(), null, Map.of(), List.of());
        String command = CommandBuilder.buildRemoteCommand(config, "/home/user/my script.sh");
        assertEquals("bash '/home/user/my script.sh'", command);
    }
}
