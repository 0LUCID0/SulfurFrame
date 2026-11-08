package dev.sulfurframe.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Answers for the IDE go through files it polls. Written next to the target and renamed into place, so the IDE
 * never reads one that exists but is still empty (it did: "inspect" got nothing back once). Java 8.
 */
final class AnswerFile {
    static void write(String file, List<String> lines) throws IOException {
        Path target = Paths.get(file), part = Paths.get(file + ".part");
        Files.write(part, lines, StandardCharsets.UTF_8);
        try { Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (IOException e) { Files.move(part, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    private AnswerFile() {}
}
