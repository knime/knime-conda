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
import org.knime.conda.envbundling.pixi.PixiBinary;
import org.knime.core.node.NodeLogger;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/*
 * TODO
 * - document expected format of the environment fragment that calls the action
 * - implement the undo method
 * - implement proper error handling (do we need to catch runtime exceptions?)
 * - how do we version this? If the format changes, we might want to support the old format
 *     + update the action automatically if the client requires a newer version of the action
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

            // TODO special parameter for the base environment? Or should it be handled by another action?

            var pixiBinary = PixiBinary.getPixiBinaryPath();
            var bundlingPath = getBundlingPath();

            LOGGER.warnWithFormat("Would install conda env from %s using %s to %s.", directory, pixiBinary,
                bundlingPath);

            // Load the example file

            // TODO
            // Replace by really needed resources
            // - Add all resources to the build.properties
            // - Remove the example foo.txt file
            var filePath = Paths.get(directory, "foo.txt");
            var exampleContent = Files.readString(filePath);
            LOGGER.warn("Read from foo.txt: " + exampleContent);
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
