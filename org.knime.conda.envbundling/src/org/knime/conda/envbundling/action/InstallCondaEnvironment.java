package org.knime.conda.envbundling.action;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.equinox.p2.engine.spi.ProvisioningAction;
import org.knime.conda.envbundling.environment.CondaEnvironmentRegistry;
import org.knime.conda.envbundling.pixi.PixiBinary;
import org.knime.core.node.NodeLogger;
import org.knime.core.util.FileUtil;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/*
 * TODO
 * - document expected format of the environment fragment that calls the action
 * - implement the undo method
 * - implement proper error handling (do we need to catch runtime exceptions?)
 * - how do we version this? If the format changes, we might want to support the old format
 *     + update the action automatically if the client requires a newer version of the action
 * - uninstall action
 */

/**
 * Provisioning action that installs a conda environment.
 *
 * @author Benjamin Wilhelm, KNIME GmbH, Berlin, Germany
 */
public class InstallCondaEnvironment extends ProvisioningAction {

    private static final NodeLogger LOGGER = NodeLogger.getLogger(InstallCondaEnvironment.class);

    private static final Bundle BUNDLE = FrameworkUtil.getBundle(ProvisioningAction.class);

    @Override
    public IStatus execute(final Map<String, Object> parameters) {
        try {
            // TODO get the parameters (path to pixi.toml, pixi.lock, channel?
            var directory = (String)parameters.get("directory");
            var name = (String)parameters.get("name");

            // TODO check if parameters are fine (not null or empty)

            // TODO special parameter for the base environment? Or should it be handled by another action?

            var pixiBinary = PixiBinary.getPixiBinaryPath();
            var bundlingPath = getBundlingPath();

            var environmentResourcesFolder = Paths.get(directory, "knime_extension_environment");

            var installedEnvRoot = bundlingPath.resolve(name);

            // Move channel to bundling folder
            FileUtil.copyDir(environmentResourcesFolder.resolve("channel").toFile(),
                installedEnvRoot.resolve("channel").toFile());

            // Pixi init
            var pb = new ProcessBuilder(pixiBinary, "init", "-i",
                environmentResourcesFolder.resolve("environment.yml").toAbsolutePath().toString());
            pb.directory(installedEnvRoot.toFile());
            var process = pb.start();
            var exitValue = process.waitFor();
            if (exitValue != 0) {
                // TODO read stdout/stderr
                throw new IllegalStateException("pixi init failed with " + exitValue);
            }

            // Pixi install
            var pb2 = new ProcessBuilder(pixiBinary, "install");
            pb2.directory(installedEnvRoot.toFile());
            var process2 = pb2.start();
            var exitValue2 = process2.waitFor();
            if (exitValue2 != 0) {
                // TODO read stdout/stderr
                throw new IllegalStateException("pixi install failed with " + exitValue2);
            }

            var envPath =
                installedEnvRoot.resolve(".pixi").resolve("envs").resolve("default").toAbsolutePath().toString();
            var envPathFile = Paths.get(directory, CondaEnvironmentRegistry.ENVIRONMENT_PATH_FILE);
            Files.writeString(envPathFile, envPath);

        } catch (Exception e) {
            e.printStackTrace();
            return error("Running action failed", e);
        }

        return Status.OK_STATUS;
    }

    @Override
    public IStatus undo(final Map<String, Object> parameters) {
        return Status.OK_STATUS;
    }

    private static Status error(final String message, final Throwable throwable) {
        return new Status(IStatus.ERROR, BUNDLE.getSymbolicName(), message, throwable);
    }

    private static Path getBundlingPath() throws URISyntaxException {
        // TODO is this correct?
        // What about the environment variable?
        // Before, it was relative to the plugins but now it's relative to the root installation
        var url = Platform.getInstallLocation().getURL();
        var externalForm = url.toExternalForm().replace(" ", "%20"); // Escape spaces // TODO other characters could still cause failures
        var uri = new URI(externalForm);
        var installPath = Paths.get(uri);
        return installPath.resolve("bundling").toAbsolutePath();
    }
}
