package org.knime.python3.nodes.testing.pixi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.knime.conda.envinstall.pixi.PixiBinary.CallResult;

class PixiUtils {
    public static Path resolveProjectDir(final String manifestText) {
        // TODO: this probably should not be hard coded
        final Path base = Paths.get(System.getProperty("user.home"), ".knime", "pixi-env-cache");
        final String hash = sha256Hex(manifestText);
        return base.resolve(hash);
    }

    public static String sha256Hex(final String s) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute SHA-256 hash.", e);
        }
    }

    public static Path saveManifestToDisk(final String manifestText) throws IOException {
        if (manifestText == null || manifestText.isBlank()) {
            throw new IllegalArgumentException("pixi.toml manifest text is empty.");
        }

        final Path projectDir = resolveProjectDir(manifestText);
        Files.createDirectories(projectDir);

        final Path manifestPath = projectDir.resolve("pixi.toml");
        Files.writeString(manifestPath, manifestText, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);
        return projectDir;
    }

    public static String getMessageFromCallResult(final CallResult callResult) {
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
