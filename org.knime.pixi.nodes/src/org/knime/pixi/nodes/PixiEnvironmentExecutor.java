package org.knime.pixi.nodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.knime.conda.envinstall.pixi.PixiBinary;
import org.knime.conda.envinstall.pixi.PixiBinary.CallResult;
import org.knime.conda.envinstall.pixi.PixiBinary.PixiBinaryLocationException;
import org.knime.core.node.ExecutionContext;
import org.knime.core.node.KNIMEException;

/**
 * Shared utility class for executing pixi environment creation logic.
 *
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 * @since 5.10
 */
@SuppressWarnings("restriction")
final class PixiEnvironmentExecutor {

    private PixiEnvironmentExecutor() {
        // Utility class
    }

    /**
     * Execute pixi install for the given manifest and return the environment path.
     *
     * @param manifestText the pixi.toml content
     * @param tomlFilePath optional path to an existing pixi.toml file (if provided, uses that file's directory)
     * @param pixiLockContent optional pixi.lock content from previous compatibility check
     * @param execCtx the execution context for progress reporting
     * @return the path to the created environment
     */
    static Path executePixiInstall(final String manifestText, final Path tomlFilePath,
        final String pixiLockContent, final ExecutionContext execCtx) {
        execCtx.setProgress(0.1, "Running pixi install");

        Path pixiHome, projectDir;
        String envName;

        // Setup
        try {
            projectDir = PixiUtils.resolveProjectDirectory(manifestText, tomlFilePath);
            envName = "default";
            pixiHome = projectDir.resolve(".pixi-home");
            Files.createDirectories(pixiHome);

            // Write pixi.lock from settings if available and not already on disk
            final Path lockFilePath = projectDir.resolve("pixi.lock");
            if (pixiLockContent != null && !pixiLockContent.isEmpty() && !Files.exists(lockFilePath)) {
                Files.writeString(lockFilePath, pixiLockContent);
                System.out.println("[PixiExecutor] Lock file written from settings to: " 
                    + lockFilePath + " (" + pixiLockContent.length() + " chars)");
            } else if (pixiLockContent != null && !pixiLockContent.isEmpty() && Files.exists(lockFilePath)) {
                System.out.println("[PixiExecutor] Lock file already exists on disk at: " + lockFilePath 
                    + " - using existing file");
            } else if (pixiLockContent == null || pixiLockContent.isEmpty()) {
                System.out.println("[PixiExecutor] No lock file content in settings - pixi will resolve dependencies");
            }
        } catch (IOException e) {
            execCtx.setMessage("Could not save pixi manifest to disk");
            throw new KNIMEException("Could not save pixi manifest to disk", e).toUnchecked();
        }

        try {
            final Map<String, String> extraEnv = Map.of("PIXI_HOME", pixiHome.toString());
            final String[] pixiArgs = {"--color", "never", "--no-progress", "install", "--environment", envName};
            final CallResult callResult = PixiBinary.callPixi(projectDir, extraEnv, pixiArgs);

            if (callResult.returnCode() != 0) {
                String errorDetails = PixiUtils.getMessageFromCallResult(callResult);
                execCtx.setMessage("Error: " + errorDetails);
                throw new KNIMEException("Pixi install failed: " + errorDetails).toUnchecked();
            }
        } catch (PixiBinaryLocationException e) {
            execCtx.setMessage("Could not find pixi executable");
            throw new KNIMEException("Could not find pixi executable", e).toUnchecked();
        } catch (IOException e) {
            execCtx.setMessage("Could not run pixi executable");
            throw new KNIMEException("Could not run pixi executable", e).toUnchecked();
        } catch (InterruptedException e) {
            execCtx.setMessage("Pixi call got interrupted");
            Thread.currentThread().interrupt();
            throw new KNIMEException("Pixi call got interrupted", e).toUnchecked();
        }

        execCtx.setProgress(1.0, "Environment ready");

        return projectDir;
    }
}
