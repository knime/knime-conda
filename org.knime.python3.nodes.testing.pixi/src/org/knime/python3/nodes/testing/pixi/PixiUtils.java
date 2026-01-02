package org.knime.python3.nodes.testing.pixi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import org.knime.conda.envinstall.pixi.PixiBinary;
import org.knime.conda.envinstall.pixi.PixiBinary.CallResult;
import org.knime.conda.envinstall.pixi.PixiBinary.PixiBinaryLocationException;
import org.knime.core.webui.node.dialog.defaultdialog.internal.button.CancelableActionHandler;
import org.knime.core.webui.node.dialog.defaultdialog.widget.handler.WidgetHandlerException;
import org.knime.node.parameters.NodeParametersInput;

/**
 * Utilities for working with Pixi environments
 *
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 */
@SuppressWarnings("restriction")
final class PixiUtils {
    private PixiUtils() {
    }

    static Path resolveProjectDir(final String manifestText) {
        // TODO: this probably should not be hard coded
        final Path base = Paths.get(System.getProperty("user.home"), ".knime", "pixi-env-cache");
        final String hash = sha256Hex(manifestText);
        return base.resolve(hash);
    }

    static String sha256Hex(final String s) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute SHA-256 hash.", e);
        }
    }

    static Path saveManifestToDisk(final String manifestText) throws IOException {
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

    static abstract class AbstractPixiLockActionHandler<Dependency>
        extends CancelableActionHandler<String, Dependency> {

        protected abstract String getTomlContent(Dependency dependency);

        @Override
        protected final String invoke(final Dependency dependency, final NodeParametersInput context)
            throws WidgetHandlerException {
            // Build TOML from package array
            String manifestText = getTomlContent(dependency);

            try {
                final Path projectDir = PixiUtils.saveManifestToDisk(manifestText);
                final Path pixiHome = projectDir.resolve(".pixi-home");
                Files.createDirectories(pixiHome);

                final Map<String, String> extraEnv = Map.of("PIXI_HOME", pixiHome.toString());

                final String[] pixiArgs = {"--color", "never", "--no-progress", "lock"};

                final CallResult callResult = PixiBinary.callPixi(projectDir, extraEnv, pixiArgs);

                if (callResult.returnCode() != 0) {
                    String errorDetails = PixiUtils.getMessageFromCallResult(callResult);
                    throw new WidgetHandlerException(errorDetails);
                }

                return "Environment is compatible with all selected operating systems.";

            } catch (IOException ex) {
                throw new WidgetHandlerException("Unknown error occurred: " + ex.getMessage());
            } catch (PixiBinaryLocationException ex) {
                throw new WidgetHandlerException("Pixi binary is not found: " + ex.getMessage());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new WidgetHandlerException("Operation was interrupted.");
            }
        }

        @Override
        protected final String getButtonText(final CancelableActionHandler.States state) {
            return switch (state) {
                case READY -> "Check compatibility";
                case CANCEL -> "Cancel";
                case DONE -> "✓ Environment is compatible";
            };
        }

        @Override
        protected final boolean isMultiUse() {
            return false;
        }
    }
}
