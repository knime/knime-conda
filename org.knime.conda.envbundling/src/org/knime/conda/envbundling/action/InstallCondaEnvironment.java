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
import java.util.regex.Pattern;

import org.apache.commons.io.file.PathUtils;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.equinox.p2.engine.spi.ProvisioningAction;
import org.knime.conda.envbundling.environment.CondaEnvironmentRegistry;
import org.knime.conda.envbundling.pixi.PixiBinary;
import org.knime.conda.envbundling.pixi.PixiBinary.CallResult;
import org.knime.conda.envbundling.pixi.PixiBinary.PixiBinaryLocationException;
import org.knime.core.node.NodeLogger;
import org.knime.core.util.FileUtil;
import org.osgi.framework.FrameworkUtil;

/**
 * <p>
 * Provisioning <em>actions</em> that install <strong>or</strong> uninstall a Pixi/Conda environment shipped inside an
 * Eclipse fragment bundle.<br/>
 * Because both directions share most of the file-system logic (path calculation, validation, cache handling, …), they
 * are implemented in this single helper class:
 * </p>
 *
 * <ul>
 * <li>{@link InstallAction} &nbsp;–&nbsp;creates the environment.</li>
 * <li>{@link UninstallAction} –&nbsp;removes the same environment again.</li>
 * </ul>
 *
 * <h3>Expected fragment layout</h3>
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
 * <h3>Action parameters</h3>
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
 * <td>Path to the fragment root (usually <code>${artifact.location}</code> in <code>p2.inf</code>); must contain the
 * <em>env</em> folder shown above.</td>
 * </tr>
 * <tr>
 * <td><code>name</code></td>
 * <td>String</td>
 * <td>✔</td>
 * <td>Name of the environment; becomes the sub-directory under <code>${installation}/bundling</code>.</td>
 * </tr>
 * </table>
 *
 * <h3>Side effects</h3>
 * <ul>
 * <li><strong>Install:</strong> creates <code>${installation}/bundling/&lt;name&gt;/pixi.toml</code>, installs the full
 * environment under <code>${installation}/bundling/&lt;name&gt;/.pixi/envs/default</code>, and writes a one-line
 * <code>plugins/&lt;fragment&gt;/environment_path.txt</code> pointing to it.</li>
 * <li><strong>Uninstall</strong> (or <code>undo()</code>): deletes the environment directory and
 * <code>environment_path.txt</code>, then invalidates {@link CondaEnvironmentRegistry}.</li>
 * </ul>
 *
 * <h3>Example <code>p2.inf</code></h3>
 *
 * <pre>
 * metaRequirements.0.namespace = org.eclipse.equinox.p2.iu
 * metaRequirements.0.name = org.knime.features.conda.envbundling.feature.group
 * metaRequirements.0.range = [5.5.0,6.0.0)
 * metaRequirements.0.greedy = true
 * metaRequirements.0.optional = false
 * instructions.install=\
 *     org.knime.conda.envbundling.installcondaenvironment(\
 *         directory:${artifact.location},\
 *         name:my_unique_environment_name\
 *     );
 *
 * instructions.uninstall=\
 *     org.knime.conda.envbundling.uninstallcondaenvironment(\
 *         directory:${artifact.location},\
 *         name:my_unique_environment_name\
 *     );
 * </pre>
 *
 * @author Benjamin Wilhelm, KNIME GmbH, Berlin, Germany
 */
public final class InstallCondaEnvironment {

    private InstallCondaEnvironment() {
    }

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

    /** Log <em>error</em> level messages to both KNIME's {@link NodeLogger} (`knime.log`) and eclipse log. */
    private static void logError(final String message, final Throwable t) {
        NODE_LOGGER.error(message, t);
        BUNDLE_LOG.log(Status.error(message, t));
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

        /** Allowed: ASCII letters, digits, dash, underscore, dot. 1–64 chars. */
        private static final Pattern VALID_ENV_NAME = Pattern.compile("^[A-Za-z0-9._-]{1,64}$"); // NOSONAR - only ASCII

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
            if (!VALID_ENV_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("The provisioning action parameter 'name' must match "
                    + VALID_ENV_NAME.pattern() + " but was: '" + name + "'");
            }

            return new Parameters(directoryPath, name);
        }
    }

    /* --------------------------------------------------------------------- */
    /* Installing environments                                               */
    /* --------------------------------------------------------------------- */

    private static void installEnvironment(final Path artifactLocation, final String environmentName)
        throws IOException, PixiBinaryLocationException, InterruptedException {
        logInfo("Installing conda environment '" + environmentName + "' from fragment: " + artifactLocation);

        var installationRoot = artifactLocation.getParent().getParent();
        var bundlingRoot = getBundlingRoot(installationRoot);
        var envResourcesFolder = artifactLocation.resolve("env");
        var envDestinationRoot = bundlingRoot.resolve(environmentName);
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
        var relativeChannelPath = getRelativeChannelPath(envDestinationRoot, channelDirSrc);
        envContent = envContent.replace("  - ./channel", "  - " + relativeChannelPath.toString().replace('\\', '/'));
        // pixi does not support pypi-options in environment.yml (checked for pixi 0.47.0).
        // -> We need to remove the --no-index and --find-links ./pypi options from the environment.yml file.
        envContent = envContent //
            .replace("- --no-index", "") //
            .replace("- --find-links ./pypi", ""); //
        Files.writeString(environmentYmlDst, envContent, StandardCharsets.UTF_8);

        /* ------------------------------------------------------------- */
        /* 2) Run "pixi init -i environment.yml"                         */
        /* ------------------------------------------------------------- */
        var initResult = PixiBinary.callPixi(envDestinationRoot, "init", "-i", environmentYmlDst.toString());
        if (!initResult.isSuccess()) {
            logError(formatPixiFailure("pixi init", initResult));
            throw new IOException("Failed to initialise Pixi project (exit code " + initResult.returnCode() + ")");
        }
        /* ------------------------------------------------------------- */
        /* 2b) Modify the pixi.toml to contain the pypi-options          */
        /* ------------------------------------------------------------- */
        var pixiTomlPath = envDestinationRoot.resolve("pixi.toml");
        var pypiDirSrc = envResourcesFolder.resolve("pypi").toAbsolutePath();
        var pypiIndexURL = pypiDirSrc.toUri().toURL();
        var pypiOptions = String.format("""

                [pypi-options]
                find-links = [{path = "%s"}]
                index-url = "%s"
                """, pypiDirSrc.toString().replace("\\", "\\\\"), pypiIndexURL);
        Files.writeString(pixiTomlPath, pypiOptions, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        /* ------------------------------------------------------------- */
        /* 3) Install the environment                                    */
        /* ------------------------------------------------------------- */
        var installResult = PixiBinary.callPixi(envDestinationRoot, "install");
        if (!installResult.isSuccess()) {
            logError(formatPixiFailure("pixi install", installResult));
            throw new IOException(
                "Installing the Pixi environment failed (exit code " + installResult.returnCode() + ")");
        }

        /* ------------------------------------------------------------- */
        /* 4) Write environment_path.txt                                 */
        /* ------------------------------------------------------------- */
        var envPath = envDestinationRoot.resolve(".pixi").resolve("envs").resolve("default");
        var envPathFile = artifactLocation.resolve(CondaEnvironmentRegistry.ENVIRONMENT_PATH_FILE);
        Path envPathToWrite;
        if (envPath.toAbsolutePath().startsWith(installationRoot.toAbsolutePath())) {
            // write relative path if the environment is inside the installation root
            envPathToWrite = installationRoot.toAbsolutePath().relativize(envPath.toAbsolutePath());
        } else {
            // write absolute path if the environment is outside the installation root
            envPathToWrite = envPath.toAbsolutePath();
        }
        Files.writeString(envPathFile, envPathToWrite.toString(), StandardCharsets.UTF_8);

        logInfo("Environment installed successfully: " + envPath);

        // Invalidate the CondaEnvironmentRegistry cache
        CondaEnvironmentRegistry.invalidateCache();
    }

    /* --------------------------------------------------------------------- */
    /* Uninstalling Environments                                             */
    /* --------------------------------------------------------------------- */

    private static void uninstallEnvironment(final Path artifactLocation, final String environmentName)
        throws IOException {
        logInfo("Uninstalling conda environment '" + environmentName + "' from fragment: " + artifactLocation);

        var installationRoot = artifactLocation.getParent().getParent();
        var envPathFile = artifactLocation.resolve(CondaEnvironmentRegistry.ENVIRONMENT_PATH_FILE);

        // Read the environment path from the file
        var envPathText = Files.readString(envPathFile, StandardCharsets.UTF_8).trim();
        var envPath = installationRoot.resolve(envPathText);

        /* <bundling_root>/<env_name>/.pixi/envs/default/
         *                 ^^^^^^^^^^            ^^^^^^^
         *                 envRoot               envPath
         */
        var envRoot = envPath.getParent().getParent().getParent();

        // Delete environment root directory
        if (Files.exists(envRoot)) {
            FileUtil.deleteRecursively(envRoot.toFile());
            logInfo("Removed environment directory: " + envRoot);
        }

        // Delete environment_path.txt file
        Files.deleteIfExists(envPathFile);

        // Invalidate the CondaEnvironmentRegistry cache
        CondaEnvironmentRegistry.invalidateCache();
    }

    /* --------------------------------------------------------------------- */
    /* Actions                                                               */
    /* --------------------------------------------------------------------- */

    /** Installs the environment contained in the fragment bundle. See {@link InstallCondaEnvironment} for details. */
    public static final class InstallAction extends ProvisioningAction {

        @Override
        public IStatus execute(final Map<String, Object> parameterMap) {
            try {
                var p = Parameters.from(parameterMap);
                installEnvironment(p.directory, p.name);
            } catch (Exception e) {
                logError("Exception while installing environment: " + e.getMessage(), e);
                return Status.error("Running InstallCondaEnvironment action failed", e);
            }

            return Status.OK_STATUS;
        }

        @Override
        public IStatus undo(final Map<String, Object> parameterMap) {
            try {
                var p = Parameters.from(parameterMap);
                uninstallEnvironment(p.directory, p.name);
            } catch (Exception e) {
                logError("Exception while undoing InstallCondaEnvironment: " + e.getMessage(), e);
                return Status.error("Undoing InstallCondaEnvironment action failed", e);
            }

            return Status.OK_STATUS;
        }
    }

    /** Uninstalls the environment that was previously installed by {@link InstallAction}. */
    public static final class UninstallAction extends ProvisioningAction {

        @Override
        public IStatus execute(final Map<String, Object> parameterMap) {
            try {
                var p = Parameters.from(parameterMap);
                uninstallEnvironment(p.directory, p.name);
            } catch (Exception e) {
                logError("Exception while uninstalling environment: " + e.getMessage(), e);
                return Status.error("Running UninstallCondaEnvironment action failed", e);
            }

            return Status.OK_STATUS;
        }

        @Override
        public IStatus undo(final Map<String, Object> parameterMap) {
            // Undo the unstall action by installing the environment again
            try {
                var p = Parameters.from(parameterMap);
                installEnvironment(p.directory, p.name);
            } catch (Exception e) {
                logError("Exception while undoing UninstallCondaEnvironment: " + e.getMessage(), e);
                return Status.error("Undoing UninstallCondaEnvironment action failed", e);
            }

            return Status.OK_STATUS;
        }
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

    /**
     * The relative path to the channel directory from the environment root. Falls back to an absolute path if the
     * environment root is on a separate volume.
     */
    private static Path getRelativeChannelPath(final Path envDestinationRoot, final Path channelDirSrc) {
        try {
            return envDestinationRoot.toAbsolutePath().relativize(channelDirSrc.toAbsolutePath());
        } catch (IllegalArgumentException ex) { // NOSONAR - we have a workaround if relativize fails
            // Different roots/volumes – fall back to absolute path
            logInfo(
                "Channel directory is on a different volume; using absolute path in environment.yml: " + channelDirSrc);
            return channelDirSrc.toAbsolutePath();
        }
    }
}
