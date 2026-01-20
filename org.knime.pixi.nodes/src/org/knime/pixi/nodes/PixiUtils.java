package org.knime.pixi.nodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import org.knime.conda.envinstall.pixi.PixiBinary;
import org.knime.conda.envinstall.pixi.PixiBinary.CallResult;
import org.knime.conda.envinstall.pixi.PixiBinary.PixiBinaryLocationException;
import org.knime.pixi.port.PixiEnvMapping;

/**
 * Utilities for working with Pixi environments
 *
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 */
@SuppressWarnings("restriction")
public final class PixiUtils {
    private PixiUtils() {
    }

    static Path resolveProjectDirectory(final String manifestText, final Path tomlFilePath) throws IOException {
        // If a file path is provided, use that file's parent directory (don't write anything)
        if (tomlFilePath != null) {
            if (!Files.exists(tomlFilePath)) {
                throw new IOException("Specified pixi.toml file does not exist: " + tomlFilePath);
            }
            if (!Files.isRegularFile(tomlFilePath)) {
                throw new IOException("Specified path is not a file: " + tomlFilePath);
            }
            // Return the parent directory; the existing pixi.toml file will be used as-is
            return tomlFilePath.getParent();
        }

        // Otherwise, use the preference-based directory and write manifest
        if (manifestText == null || manifestText.isBlank()) {
            throw new IllegalArgumentException("pixi.toml manifest text is empty.");
        }

        final Path projectDir = PixiEnvMapping.resolvePixiEnvDirectory(manifestText);
        Files.createDirectories(projectDir);

        final Path manifestPath = projectDir.resolve("pixi.toml");
        // Only write if file doesn't exist, or overwrite with the provided content
        if (!Files.exists(manifestPath)) {
            Files.writeString(manifestPath, manifestText, StandardCharsets.UTF_8, StandardOpenOption.CREATE);
        } else {
            // File exists - overwrite with new content (node was reconfigured with different TOML)
            Files.writeString(manifestPath, manifestText, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        }
        return projectDir;
    }

    static String getMessageFromCallResult(final CallResult callResult) {
        // TODO implement this in a better way
        final String stdout = callResult.stdout() == null ? "" : callResult.stdout();
        final String stderr = callResult.stderr() == null ? "" : callResult.stderr();

        final String msg = "pixi install failed (exit code " + callResult.returnCode() + ").\n"
            + (stderr.isBlank() ? "" : "---- stderr ----\n" + stderr + "\n")
            + (stdout.isBlank() ? "" : "---- stdout ----\n" + stdout + "\n");
        return msg;
    }

    static Path resolvePython(final Path projectDir, final String envName) {
        Path envDir = projectDir.resolve(".pixi").resolve("envs").resolve(envName);
        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        return isWin ? envDir.resolve("python.exe") : envDir.resolve("bin").resolve("python");
    }
}
