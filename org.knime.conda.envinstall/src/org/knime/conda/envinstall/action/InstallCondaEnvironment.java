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
package org.knime.conda.envinstall.action;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import org.apache.commons.io.file.PathUtils;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.equinox.p2.engine.spi.ProvisioningAction;
import org.knime.conda.envinstall.pixi.PixiBinary;
import org.knime.conda.envinstall.pixi.PixiBinary.CallResult;
import org.knime.conda.envinstall.pixi.PixiBinary.PixiBinaryLocationException;
import org.osgi.framework.FrameworkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * ├─ pixi.lock                    # Pixi lockfile
 * ├─ env/
 * │   ├─ channel/                 # Local Conda channel
 * │   │   ├─ linux-64/            # Platform-specific packages ("linux-64", "win-64", "osx-64", "osx-arm64")
 * │   │   │   ├─ package1.conda
 * │   │   │   └─ package2.conda
 * │   │   └─ noarch/              # Noarch packages
 * │   │       ├─ package1.conda
 * │   │       └─ package2.conda
 * │   └─ pypi/                    # Local PyPI index
 * │       ├─ package1.whl
 * │       └─ package2.whl
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
 * <em>env</em> folder and <em>pixi.lock</em> shown above.</td>
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
 * <code>environment_path.txt</code>.</li>
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
 *     org.knime.conda.envinstall.installcondaenvironment(\
 *         directory:${artifact.location},\
 *         name:my_unique_environment_name\
 *     );
 *
 * instructions.uninstall=\
 *     org.knime.conda.envinstall.uninstallcondaenvironment(\
 *         directory:${artifact.location},\
 *         name:my_unique_environment_name\
 *     );
 * </pre>
 *
 * @author Benjamin Wilhelm, KNIME GmbH, Berlin, Germany
 * @since 5.5
 */
public final class InstallCondaEnvironment {

    /** The name of the file that contains the path to the environment location */
    public static final String ENVIRONMENT_PATH_FILE = "environment_path.txt";

    private static final String PIXI_CACHE_DIRECTORY_NAME = ".pixi-cache";

    private InstallCondaEnvironment() {
    }

    /* --------------------------------------------------------------------- */
    /* Logging                                                               */
    /* --------------------------------------------------------------------- */

    private static final ILog BUNDLE_LOG = Platform.getLog(FrameworkUtil.getBundle(InstallCondaEnvironment.class));

    private static final Logger LOGGER = LoggerFactory.getLogger(InstallCondaEnvironment.class);

    /** Log <em>error</em> level messages to both KNIME's {@link NodeLogger} (`knime.log`) and eclipse log. */
    private static void logError(final String message) {
        BUNDLE_LOG.log(Status.error(message));
        LOGGER.error(message);
    }

    /** Log <em>error</em> level messages to both KNIME's {@link NodeLogger} (`knime.log`) and eclipse log. */
    private static void logError(final String message, final Throwable t) {
        BUNDLE_LOG.log(Status.error(message, t));
        LOGGER.error(message, t);
    }

    /** Log <em>info</em> level messages to both KNIME's {@link NodeLogger} (`knime.log`) and eclipse log. */
    private static void logInfo(final String message) {
        BUNDLE_LOG.log(Status.info(message));
        LOGGER.warn("INFO: " + message); // TODO adapt level
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
        notifyEnvironmentListeners(InstallPhase.START, environmentName);
        try {
            logInfo("Installing conda environment '" + environmentName + "' from fragment: " + artifactLocation);

            var installationRoot = artifactLocation.getParent().getParent();
            var bundlingRoot = getBundlingRoot(installationRoot);
            var envResourcesFolder = artifactLocation.resolve("env");
            var envDestinationRoot = bundlingRoot.resolve(environmentName);

            // Check if the destination directory does not exist or is empty and writable before doing anything else
            checkDestinationDirectory(envDestinationRoot);

            /* ------------------------------------------------------------- */
            /* 1) Write environment_path.txt                                 */
            /* ------------------------------------------------------------- */
            // Note that we do this first to ensure that the environment path is always written, even if the
            // installation fails later to be able to clean up the environment directory.
            var envPath = envDestinationRoot.resolve(".pixi").resolve("envs").resolve("default");
            var envPathFile = artifactLocation.resolve(ENVIRONMENT_PATH_FILE);
            Path envPathToWrite;
            if (envPath.toAbsolutePath().startsWith(installationRoot.toAbsolutePath())) {
                // write relative path if the environment is inside the installation root
                envPathToWrite = installationRoot.toAbsolutePath().relativize(envPath.toAbsolutePath());
            } else {
                // write absolute path if the environment is outside the installation root
                envPathToWrite = envPath.toAbsolutePath();
            }
            Files.writeString(envPathFile, envPathToWrite.toString(), StandardCharsets.UTF_8);

            /* ------------------------------------------------------------- */
            /* 2) Create the environment root directory                      */
            /* ------------------------------------------------------------- */
            Files.createDirectories(envDestinationRoot);

            /* ------------------------------------------------------------- */
            /* 3) Create the pixi.lock/toml files with local URLs/Paths      */
            /* ------------------------------------------------------------- */
            var pixiLockSrc = artifactLocation.resolve("pixi.lock");
            var pixiLockDst = envDestinationRoot.resolve("pixi.lock");
            var pixiTomlDst = envDestinationRoot.resolve("pixi.toml");

            // Read the lockfile and make the URLs local and write it to the destination
            var pixiLockfile = PixiLockfileUtil.readLockfile(pixiLockSrc);
            PixiLockfileUtil.makeURLsLocal(pixiLockfile, "default", envResourcesFolder);
            PixiLockfileUtil.writeLockfile(pixiLockfile, pixiLockDst);

            // Create a minimal pixi.toml file in the destination
            Files.writeString(pixiTomlDst, """
                    [workspace]
                    channels = []
                    platforms = ["win-64", "linux-64", "osx-64", "osx-arm64"]
                    """);

            /* ------------------------------------------------------------- */
            /* 4) Install the environment                                    */
            /* ------------------------------------------------------------- */
            var pixiCacheDir = bundlingRoot.resolve(PIXI_CACHE_DIRECTORY_NAME).toAbsolutePath().toString();
            var envVars = Map.of("PIXI_CACHE_DIR", pixiCacheDir);
            var installResult = PixiBinary.callPixi(envDestinationRoot, envVars, "install", "--frozen");
            if (!installResult.isSuccess()) {
                logError(formatPixiFailure("pixi install", installResult));
                throw new IOException(
                    "Installing the Pixi environment failed (exit code " + installResult.returnCode() + ")");
            }

            logInfo("Environment installed successfully: " + envPath);
        } finally {
            notifyEnvironmentListeners(InstallPhase.END, environmentName);
        }
    }

    /* --------------------------------------------------------------------- */
    /* Uninstalling Environments                                             */
    /* --------------------------------------------------------------------- */

    private static void uninstallEnvironment(final Path artifactLocation, final String environmentName)
        throws IOException {
        notifyEnvironmentListeners(InstallPhase.START, environmentName);
        try {
            logInfo("Uninstalling conda environment '" + environmentName + "' from fragment: " + artifactLocation);

            var installationRoot = artifactLocation.getParent().getParent();
            var envPathFile = artifactLocation.resolve(ENVIRONMENT_PATH_FILE);

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
                PathUtils.deleteDirectory(envRoot);
                logInfo("Removed environment directory: " + envRoot);
            }

            // Delete environment_path.txt file
            Files.deleteIfExists(envPathFile);
        } finally {
            notifyEnvironmentListeners(InstallPhase.END, environmentName);
        }
    }

    /* --------------------------------------------------------------------- */
    /* Environment install listeners                                         */
    /* --------------------------------------------------------------------- */

    /**
     * Listener for environment installation events.
     */
    public interface EnvironmentInstallListener {
        /**
         * Called before environment installation starts.
         *
         * @param environmentName Which environment gets created
         */
        void onInstallStart(String environmentName);

        /**
         * Called after environment installation ends (success or failure).
         *
         * @param environmentName The environment that got created (or at least attempted to)
         */
        void onInstallEnd(String environmentName);
    }

    private static final List<EnvironmentInstallListener> ENV_INSTALL_LISTENERS = new CopyOnWriteArrayList<>();

    /**
     * Register a listener for environment installation events.
     *
     * @param listener Add this listener to be notified on environment installations
     */
    public static void registerEnvironmentInstallListener(final EnvironmentInstallListener listener) {
        ENV_INSTALL_LISTENERS.add(listener); // NOSONAR - this is slow but happens rarely
    }

    /**
     * De-register a listener for environment installation events.
     *
     * @param listener Remove a listener for environment installations
     */
    public static void deregisterEnvironmentInstallListener(final EnvironmentInstallListener listener) {
        ENV_INSTALL_LISTENERS.remove(listener); // NOSONAR - this is slow but happens rarely
    }

    private enum InstallPhase {
            START, END
    }

    /**
     * Call all listeners for an install or uninstall event
     */
    private static void notifyEnvironmentListeners(final InstallPhase phase, final String environmentName) {
        for (EnvironmentInstallListener l : ENV_INSTALL_LISTENERS) {
            try {
                if (phase == InstallPhase.START) {
                    l.onInstallStart(environmentName);
                } else {
                    l.onInstallEnd(environmentName);
                }
            } catch (Exception e) { // NOSONAR - we want to catch all exceptions to not break the installation process
                logError("Exception when notifying EnvironmentInstallListener about install " + phase + " : "
                    + e.getMessage(), e);
            }
        }
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
}
