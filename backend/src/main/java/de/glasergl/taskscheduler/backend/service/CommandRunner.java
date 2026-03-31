package de.glasergl.taskscheduler.backend.service;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

public final class CommandRunner {
    public Process start(String command) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(buildShellCommand(command));
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        return processBuilder.start();
    }

    private List<String> buildShellCommand(String command) {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return List.of("cmd.exe", "/c", command);
        }
        return List.of("sh", "-lc", command);
    }
}

