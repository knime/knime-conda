package org.knime.python3.nodes.testing.pixi;

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
 */
final class PixiEnvironmentExecutor {

    private PixiEnvironmentExecutor() {
        // Utility class
    }

    /**
     * Execute pixi install for the given manifest and return the Python executable path.
     *
     * @param manifestText the pixi.toml content
     * @param execCtx the execution context for progress reporting
     * @return the path to the Python executable in the created environment
     */
    static Path executePixiInstall(final String manifestText, final ExecutionContext execCtx) {
        execCtx.setProgress(0.1, "Running pixi install");

        Path pixiHome, projectDir;
        String envName;

        // Setup
        try {
            projectDir = PixiUtils.saveManifestToDisk(manifestText);
            envName = "default";
            pixiHome = projectDir.resolve(".pixi-home");
            Files.createDirectories(pixiHome);
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

        return PixiUtils.resolvePython(projectDir, envName);
    }
}
