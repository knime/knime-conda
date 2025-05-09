/*
 * ------------------------------------------------------------------------
 *
 *  Copyright by KNIME AG, Zurich, Switzerland
 *  Website: http://www.knime.com; Email: contact@knime.com
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License, Version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 *  Additional permission under GNU GPL version 3 section 7:
 *
 *  KNIME interoperates with ECLIPSE solely via ECLIPSE's plug-in APIs.
 *  Hence, KNIME and ECLIPSE are both independent programs and are not
 *  derived from each other. Should, however, the interpretation of the
 *  GNU GPL Version 3 ("License") under any applicable laws result in
 *  KNIME and ECLIPSE being a combined program, KNIME AG herewith grants
 *  you the additional permission to use and propagate KNIME together with
 *  ECLIPSE with only the license terms in place for ECLIPSE applying to
 *  ECLIPSE and the GNU GPL Version 3 applying for KNIME, provided the
 *  license terms of ECLIPSE themselves allow for the respective use and
 *  propagation of ECLIPSE together with KNIME.
 *
 *  Additional permission relating to nodes for KNIME that extend the Node
 *  Extension (and in particular that are based on subclasses of NodeModel,
 *  NodeDialog, and NodeView) and that only interoperate with KNIME through
 *  standard APIs ("Nodes"):
 *  Nodes are deemed to be separate and independent programs and to not be
 *  covered works.  Notwithstanding anything to the contrary in the
 *  License, the License does not apply to Nodes, you are not required to
 *  license Nodes under the License, and you are granted a license to
 *  prepare and propagate Nodes, in each case even if such Nodes are
 *  propagated with or for interoperation with KNIME.  The owner of a Node
 *  may freely choose the license terms applicable to such Node, including
 *  when such Node is propagated with or for interoperation with KNIME.
 * ---------------------------------------------------------------------
 */
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

            if (directory == null || name == null) {
                return error("Missing parameters", new IllegalArgumentException("Missing parameters"));
            }
            if (!Files.isDirectory(Paths.get(directory))) {
                return error("Directory does not exist", new IllegalArgumentException("Directory does not exist"));
            }
            LOGGER.info("Installing conda environment " + name + "with pixi in " + directory);
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

            // Invalidate the CondaEnvironmentRegistry cache
            CondaEnvironmentRegistry.invalidateCache();

        } catch (Exception e) {
            e.printStackTrace();
            return error("Running action failed", e);
        }

        return Status.OK_STATUS;
    }

    @Override
    public IStatus undo(final Map<String, Object> parameters) {
        // Invalidate the CondaEnvironmentRegistry cache
        CondaEnvironmentRegistry.invalidateCache();
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
