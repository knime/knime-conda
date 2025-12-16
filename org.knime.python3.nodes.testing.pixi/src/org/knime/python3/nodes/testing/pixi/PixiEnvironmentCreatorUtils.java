package org.knime.python3.nodes.testing.pixi;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.stream.Stream;

import org.knime.core.node.ExecutionContext;

/**
 * Utility helpers abstracted from the Python prototype.
 *
 * These methods should be replaced with production-ready implementations hooked into KNIME/pixi integration.
 */
final class PixiEnvironmentCreatorUtils {

    private PixiEnvironmentCreatorUtils() {}

    static Optional<String> locatePixiExecutable() {
        // TODO Implement real autodetection (search PATH / KNIME bundled pixi)
        return Optional.empty();
    }

    static boolean validateExecutable(final String path) {
        return path != null && !path.isBlank() && new File(path).exists() && new File(path).canExecute();
    }

    static boolean fileExists(final String path) {
        return path != null && new File(path).exists();
    }

    static String buildManifestText(final PixiEnvironmentCreatorNodeParameters params) {
        final var basePkg = params.m_baseEnvironment.getValue().getPackageName();
        final var sb = new StringBuilder();
        sb.append("[workspace]\n");
        sb.append("channels = [\"knime\", \"conda-forge\"]\n");
        sb.append("platforms = [\"win-64\", \"linux-64\", \"osx-64\", \"osx-arm64\"]\n\n");
        sb.append("[dependencies]\n");
        sb.append(basePkg).append(" = \"*\"\n");

        final var pipLines = new StringBuilder();

        for (var spec : params.m_additionalPackages) {
            final String name = spec.m_name == null ? "" : spec.m_name.trim();
            if (name.isEmpty()) {
                continue;
            }
            final var bounds = Stream.of(
                    spec.m_lowerBound == null ? "" : spec.m_lowerBound.trim().isEmpty() ? "" : ">=" + spec.m_lowerBound.trim(),
                    spec.m_upperBound == null ? "" : spec.m_upperBound.trim().isEmpty() ? "" : "<" + spec.m_upperBound.trim()
                )
                .filter(s -> !s.isEmpty())
                .toList();

            final String versionExpr = bounds.isEmpty() ? "*" : String.join(",", bounds);

            final var installer = spec.m_installer.id();
            if (installer == PixiEnvironmentCreatorNodeParameters.Installer.CONDA) {
                sb.append(name).append(" = \"").append(versionExpr).append("\"\n");
            } else {
                pipLines.append(name).append(" = \"").append(versionExpr).append("\"\n");
            }
        }

        if (pipLines.length() > 0) {
            sb.append("\n[pypi-dependencies]\n");
            sb.append(pipLines);
        }
        sb.append("\n");
        return sb.toString();
    }

    record CacheResult(String environmentDir, String manifestHash) {}

    static CacheResult createOrGetCachedEnvironment(final String manifestText,
                                                    final String pixiExe,
                                                    final String sourceFormat,
                                                    final String nodeType,
                                                    final ExecutionContext execCtx) {
        // TODO Replace with real caching logic (invoke pixi).
        // Simple placeholder: hash manifest, create temp dir keyed by hash.
        final String hash = sha256(manifestText);
        try {
            final Path baseDir = Files.createTempDirectory("pixi-env-cache-");
            final Path envDir = baseDir.resolve("env-" + hash);
            Files.createDirectories(envDir);
            // placeholder file w/ manifest
            Files.writeString(envDir.resolve("pixi.toml"), manifestText, StandardCharsets.UTF_8);
            return new CacheResult(envDir.toAbsolutePath().toString(), hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to prepare cached environment", e);
        }
    }

    static String derivePythonExecutable(final String envDir) {
        // TODO Implement platform‑specific resolution. Placeholder assumes bin/python.
        return Path.of(envDir, "bin", "python").toString();
    }

    static String joinAddedPackageNames(final PixiEnvironmentCreatorNodeParameters params) {
        final var joined = Stream.of(params.m_additionalPackages)
            .map(p -> p.m_name == null ? "" : p.m_name.trim())
            .filter(s -> !s.isEmpty())
            .toList();
        return String.join(",", joined);
    }

    private static String sha256(final String s) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 algorithm missing", e);
        }
    }
}