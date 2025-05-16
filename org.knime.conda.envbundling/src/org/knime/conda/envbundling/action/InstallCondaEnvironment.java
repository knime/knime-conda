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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.apache.commons.io.file.PathUtils;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.equinox.p2.engine.spi.ProvisioningAction;
import org.knime.conda.envbundling.environment.CondaEnvironmentRegistry;
import org.knime.conda.envbundling.pixi.PixiBinary;
import org.knime.conda.envbundling.pixi.PixiBinary.CallResult;
import org.knime.core.node.NodeLogger;
import org.knime.core.util.FileUtil;
import org.osgi.framework.FrameworkUtil;

/**
 * <p>
 * Provisioning action that installs a Pixi/Conda environment contained in a fragment bundle.<br/>
 * The fragment <strong>must</strong> adhere to the following structure (relative to the fragment root):
 * </p>
 *
 * <pre>
 * ├─ META-INF/
 * ├─ env/
 * │   ├─ environment.yml   # Environment file referencing the local "./channel" directory
 * │   └─ channel/          # Local Conda channel (sub‑directories "noarch" / platform‑specific)
 * │       └─ ...
 * └─ ...
 * </pre>
 *
 * <h3>Parameters</h3>
 * <table border="1" cellpadding="4" cellspacing="0">
 * <tr>
 * <th>Name</th>
 * <th>Type</th>
 * <th>Required</th>
 * <th>Description</th>
 * </tr>
 * <tr>
 * <td><code>directory</code></td>
 * <td>String</td>
 * <td>✔</td>
 * <td>Path to the artifact location (usually set to <code>${artifact.location}</code> in <code>p2.inf</code>), which
 * contains the <em>env</em> folder as shown above.</td>
 * </tr>
 * <tr>
 * <td><code>name</code></td>
 * <td>String</td>
 * <td>✔</td>
 * <td>Name of the environment.&nbsp;Used as sub‑directory below <code>${installation}/bundling</code>.</td>
 * </tr>
 * </table>
 *
 * <p>
 * After successful execution the following additional artefacts are created:
 * </p>
 * <ul>
 * <li><code>${installation}/bundling/&lt;name&gt;/pixi.toml</code> and the full environment under
 * <code>${installation}/bundling/&lt;name&gt;/.pixi/envs/default</code></li>
 * <li><code>plugins/&lt;fragment&gt;/environment_path.txt</code> containing the absolute path to the new environment
 * (one line)</li>
 * </ul>
 *
 * <p>
 * The {@link #undo(Map)} method removes the environment directory as well as the <code>environment_path.txt</code> file
 * and clears the {@link CondaEnvironmentRegistry} cache.
 * </p>
 *
 * @author Benjamin Wilhelm, KNIME GmbH, Berlin, Germany
 */
public final class InstallCondaEnvironment extends ProvisioningAction {

    /* --------------------------------------------------------------------- */
    /* Logging                                                               */
    /* --------------------------------------------------------------------- */

    private static final NodeLogger NODE_LOGGER = NodeLogger.getLogger(InstallCondaEnvironment.class);

    private static final ILog BUNDLE_LOG = Platform.getLog(FrameworkUtil.getBundle(InstallCondaEnvironment.class));

    /** Log <em>error</em> level messages to both KNIME's {@link NodeLogger} (`knime.log`) and eclipse log. */
    private static void logError(final String message) {
        NODE_LOGGER.error(message);
        BUNDLE_LOG.log(Status.error(message));
    }

    /** Log <em>info</em> level messages to both KNIME's {@link NodeLogger} (`knime.log`) and eclipse log. */
    private static void logInfo(final String message) {
        NODE_LOGGER.info(message);
        BUNDLE_LOG.log(Status.info(message));
    }

    /* --------------------------------------------------------------------- */
    /* Parameter handling                                                    */
    /* --------------------------------------------------------------------- */

    /**
     * @param directory the path to the fragment directory that executes the action. The directory must have the
     *            expected structure as documented in {@link InstallCondaEnvironment}.
     * @param name the environment name
     */
    private record Parameters(Path directory, String name) {

        /**
         * Parses and validates the parameter map.
         *
         * @throws IllegalArgumentException if any required argument is missing or malformed
         */
        private static Parameters from(final Map<String, Object> parameters) {
            var directory = (String)parameters.get("directory");
            if (directory == null) {
                throw new IllegalArgumentException("The provisioning action parameter 'directory' is missing.");
            }
            var directoryPath = Paths.get(directory);
            if (!Files.isDirectory(directoryPath)) {
                throw new IllegalArgumentException(String.format(
                    "The provisioning action parameter 'directory' with the value '%s' does not point to a directory.",
                    directory));
            }

            var name = (String)parameters.get("name");
            if (name == null) {
                throw new IllegalArgumentException("The provisioning action parameter 'name' is missing.");
            }
            if (name.isBlank()) {
                throw new IllegalArgumentException("The provisioning action parameter 'name' is blank.");
            }

            return new Parameters(directoryPath, name);
        }
    }

    /* --------------------------------------------------------------------- */
    /* Execute                                                               */
    /* --------------------------------------------------------------------- */

    @Override
    public IStatus execute(final Map<String, Object> parameterMap) {
        try {
            var p = Parameters.from(parameterMap);

            logInfo("Installing conda environment '" + p.name + "' from fragment: " + p.directory);

            var installationRoot = p.directory.getParent().getParent();
            var bundlingRoot = getBundlingRoot(installationRoot);
            var envResourcesFolder = p.directory.resolve("env");
            var envDestinationRoot = bundlingRoot.resolve(p.name);
            checkDestinationDirectory(envDestinationRoot);
            Files.createDirectories(envDestinationRoot);

            /* ------------------------------------------------------------- */
            /* 1) Copy environment.yml adjusting the channel path            */
            /* ------------------------------------------------------------- */
            var environmentYmlSrc = envResourcesFolder.resolve("environment.yml");
            var environmentYmlDst = envDestinationRoot.resolve("environment.yml");

            var channelDirSrc = envResourcesFolder.resolve("channel");
            if (!Files.isDirectory(channelDirSrc)) {
                throw new IllegalStateException(
                    "Expected 'channel' directory next to environment.yml in " + envResourcesFolder);
            }
            var envContent = Files.readString(environmentYmlSrc, StandardCharsets.UTF_8);
            var relativeChannelPath = envDestinationRoot.toAbsolutePath().relativize(channelDirSrc.toAbsolutePath());
            envContent =
                envContent.replace("  - ./channel", "  - " + relativeChannelPath.toString().replace('\\', '/'));
            Files.writeString(environmentYmlDst, envContent, StandardCharsets.UTF_8);

            /* ------------------------------------------------------------- */
            /* 2) Run "pixi init -i environment.yml"                         */
            /* ------------------------------------------------------------- */
            var initResult = PixiBinary.callPixi(envDestinationRoot, "init", "-i", environmentYmlDst.toString());
            if (!initResult.isSuccess()) {
                logError(formatPixiFailure("pixi init", initResult));
                return Status.error("Failed to initialise Pixi project (exit code " + initResult.returnCode() + ")");
            }

            /* ------------------------------------------------------------- */
            /* 3) Install the environment                                    */
            /* ------------------------------------------------------------- */
            var installResult = PixiBinary.callPixi(envDestinationRoot, "install");
            if (!installResult.isSuccess()) {
                logError(formatPixiFailure("pixi install", installResult));
                return Status
                    .error("Installing the Pixi environment failed (exit code " + installResult.returnCode() + ")");
            }

            /* ------------------------------------------------------------- */
            /* 4) Write environment_path.txt                                 */
            /* ------------------------------------------------------------- */
            var envPath = envDestinationRoot.resolve(".pixi").resolve("envs").resolve("default");
            var envPathFile = p.directory.resolve(CondaEnvironmentRegistry.ENVIRONMENT_PATH_FILE);
            Path envPathToWrite;
            if (envPath.toAbsolutePath().startsWith(installationRoot.toAbsolutePath())) {
                // write relative path if the environment is inside the installation root
                envPathToWrite = installationRoot.toAbsolutePath().relativize(envPath.toAbsolutePath());
            } else {
                // write absolute path if the environment is outside the installation root
                envPathToWrite = envPath.toAbsolutePath();
            }
            Files.writeString(envPathFile, envPathToWrite.toString(), StandardCharsets.UTF_8);

            // Invalidate the CondaEnvironmentRegistry cache
            CondaEnvironmentRegistry.invalidateCache();

            logInfo("Environment installed successfully: " + envPath);
        } catch (Exception e) {
            logError("Exception while installing environment: " + e.getMessage());
            return Status.error("Running InstallCondaEnvironment action failed", e);
        }

        return Status.OK_STATUS;
    }

    /* --------------------------------------------------------------------- */
    /* Undo                                                                  */
    /* --------------------------------------------------------------------- */

    @Override
    public IStatus undo(final Map<String, Object> parameterMap) {
        // TODO move into utility class to be reused in uninstall action
        try {
            var p = Parameters.from(parameterMap);

            var installationRoot = p.directory.getParent().getParent();
            var bundlingRoot = getBundlingRoot(installationRoot);
            var envDestinationRoot = bundlingRoot.resolve(p.name);
            var envPathFile = p.directory.resolve(CondaEnvironmentRegistry.ENVIRONMENT_PATH_FILE);

            // Delete environment directory (ignore if it does not exist)
            if (Files.exists(envDestinationRoot)) {
                FileUtil.deleteRecursively(envDestinationRoot.toFile());
                logInfo("Removed environment directory: " + envDestinationRoot);
            }

            // Delete environment_path.txt file
            Files.deleteIfExists(envPathFile);

            CondaEnvironmentRegistry.invalidateCache();
        } catch (Exception e) {
            logError("Exception while undoing InstallCondaEnvironment: " + e.getMessage());
            return Status.error("Undoing InstallCondaEnvironment action failed", e);
        }

        return Status.OK_STATUS;
    }

    /* --------------------------------------------------------------------- */
    /* Helpers                                                               */
    /* --------------------------------------------------------------------- */

    private static String formatPixiFailure(final String command, final CallResult result) {
        return command + " failed with exit code " + result.returnCode() + "\nSTDOUT:\n" + result.stdout()
            + "\nSTDERR:\n" + result.stderr();
    }

    private static Path getBundlingRoot(final Path installationRoot) throws IOException {
        var bundlingPathFromVar = System.getenv("KNIME_PYTHON_BUNDLING_PATH");
        Path path;
        if (bundlingPathFromVar != null && !bundlingPathFromVar.isBlank()) {
            path = Paths.get(bundlingPathFromVar);
        } else {
            path = installationRoot.resolve("bundling").toAbsolutePath();
        }

        if (Files.notExists(path)) {
            try {
                Files.createDirectories(path);
                logInfo("Created bundling root directory: " + path);
            } catch (IOException ioe) {
                throw new IOException("Unable to create bundling directory " + path + ": " + ioe.getMessage(), ioe);
            }
        }
        return path;
    }

    /** Throws if the destination directory already exists and is not empty or not writable. */
    private static void checkDestinationDirectory(final Path destination) throws IOException {
        if (!Files.exists(destination)) {
            return;
        }
        if (Files.isDirectory(destination)) {
            // check if the directory is empty and writable
            if (!PathUtils.isEmptyDirectory(destination)) {
                throw new IOException(
                    "Environment destination directory already exists and is not empty: " + destination);
            }
            if (!Files.isWritable(destination)) {
                throw new IOException("Environment destination directory is not writable: " + destination);
            }
        } else {
            // if it exists but is not a directory, throw an exception
            throw new IOException("Environment destination path exists and is not a directory: " + destination);
        }
    }
}
