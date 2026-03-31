package de.glasergl.taskscheduler.backend.service;

import de.glasergl.taskscheduler.backend.model.ExecutionRecord;
import de.glasergl.taskscheduler.backend.model.PersistedState;
import de.glasergl.taskscheduler.backend.model.ScheduledTask;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.List;

public final class JsonStateStore {
    private final Path storagePath;
    private final ObjectMapper objectMapper;

    public JsonStateStore(Path storagePath, ObjectMapper objectMapper) {
        this.storagePath = storagePath;
        this.objectMapper = objectMapper;
    }

    public PersistedState load() throws IOException {
        if (Files.notExists(storagePath) || Files.size(storagePath) == 0) {
            return new PersistedState(List.of(), List.of());
        }
        return objectMapper.readValue(storagePath.toFile(), PersistedState.class);
    }

    public synchronized void save(Collection<ScheduledTask> tasks, Collection<ExecutionRecord> executionHistory) throws IOException {
        Files.createDirectories(storagePath.getParent());

        PersistedState persistedState = new PersistedState(List.copyOf(tasks), List.copyOf(executionHistory));
        Path tempFile = storagePath.resolveSibling(storagePath.getFileName() + ".tmp");

        byte[] jsonBytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(persistedState);
        Files.write(tempFile, jsonBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

        try {
            Files.move(tempFile, storagePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            Files.move(tempFile, storagePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
