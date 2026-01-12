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

    static abstract class AbstractPixiLockActionHandler<Dependency>
        extends CancelableActionHandler<String, Dependency> {

        protected abstract String getTomlContent(Dependency dependency);

        @Override
        protected final String invoke(final Dependency dependency, final NodeParametersInput context)
            throws WidgetHandlerException {
            // Build TOML from package array
            String manifestText = getTomlContent(dependency);

            try {
                final Path projectDir = PixiUtils.resolveProjectDirectory(manifestText, null);
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
