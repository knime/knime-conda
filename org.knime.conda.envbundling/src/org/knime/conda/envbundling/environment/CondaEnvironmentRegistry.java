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
 *
 * History
 *   Mar 21, 2022 (benjamin): created
 */
package org.knime.conda.envbundling.environment;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.file.PathUtils;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IExtension;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.knime.conda.envbundling.CondaEnvironmentBundlingUtils;
import org.knime.conda.envinstall.action.InstallCondaEnvironment;
import org.knime.conda.envinstall.action.InstallCondaEnvironment.EnvironmentInstallListener;
import org.knime.conda.envinstall.pixi.PixiBinary.PixiBinaryLocationException;
import org.knime.core.node.NodeLogger;
import org.osgi.framework.Bundle;
import org.osgi.framework.Version;

/**
 * A registry for bundled conda channels.
 *
 * @author Benjamin Wilhelm, KNIME GmbH, Konstanz, Germany
 */
public final class CondaEnvironmentRegistry {

    /*
     * NOTE: This class handles environments both, environments that were created on installation time and environments
     * that were created on startup.
     * - Environments created on installation time by the InstallCondaEnvironment p2 action or by the ShellExec p2
     *   action are labeled as "install-created" environments.
     * - Environments created on startup are labeled as "startup-created" environments.
     */

    private static final String EXT_POINT_ID = "org.knime.conda.envbundling.CondaEnvironment";

    /**
     * The old name of the folder containing the environment. Each fragment of a plugin which registers a
     * CondaEnvironment must have this folder at its root.
     */
    public static final String ENV_FOLDER_NAME = "env";

    /**
     * The name of the file that contains the path to the environment location
     *
     * @since 5.4
     * @deprecated use {@link InstallCondaEnvironment#ENVIRONMENT_PATH_FILE} instead.
     */
    @Deprecated(since = "5.5")
    public static final String ENVIRONMENT_PATH_FILE = InstallCondaEnvironment.ENVIRONMENT_PATH_FILE;

    /**
     * The new name of the folder containing the all conda environments.
     */
    public static final String BUNDLE_PREFIX = "bundling" + File.separator + "envs";

    private static final NodeLogger LOGGER = NodeLogger.getLogger(CondaEnvironmentRegistry.class);

    // Use AtomicReference to allow thread-safe invalidation and lazy initialization
    private static final AtomicReference<Map<String, CondaEnvironment>> m_environments = new AtomicReference<>(null);

    /** Whether to skip the installation of the conda environments on startup instead of installation time. */
    public static final boolean SKIP_INSTALL_CONDA_ENVIRONMENT_ON_STARTUP =
        Boolean.getBoolean("knime.conda.skip_install_envs_on_startup");

    /** Flag to track if conda environment installation is currently in progress. */
    private static final AtomicBoolean s_environmentInstallationInProgress = new AtomicBoolean(false);

    static {
        InstallCondaEnvironment.registerEnvironmentInstallListener(new EnvironmentInstallListener() {
            @Override
            public void onInstallStart(final String environmentName) {
                // No action needed at the start of the installation
            }

            @Override
            public void onInstallEnd(final String environmentName) {
                // Invalidate the cache when an environment is installed
                invalidateCache();
            }
        });
    }

    private CondaEnvironmentRegistry() {
        // Private constructor to prevent instantiation
    }

    /**
     * Invalidate the cached environments map. This should be called whenever an extension is installed or uninstalled.
     *
     * @since 5.5
     */
    public static void invalidateCache() {
        LOGGER.info("Invalidating CondaEnvironmentRegistry cache.");
        m_environments.set(null);
    }

    /**
     * Get the Conda environment with the given name.
     *
     * @param name the unique name of the requested environment
     * @return the {@link CondaEnvironment} which contains the path to the environment on disk
     */
    public static CondaEnvironment getEnvironment(final String name) {
        return getEnvironments().get(name);
    }

    /**
     * Checks if conda environment installation is currently in progress.
     *
     * @return true if environment installation is currently running
     * @since 5.9
     */
    public static boolean isEnvironmentInstallationInProgress() {
        return s_environmentInstallationInProgress.get();
    }

    /** @return a map of all environments that are installed. */
    public static Map<String, CondaEnvironment> getEnvironments() {
        if (m_environments.get() == null) {
            initializeEnvironments(false);
        }
        return m_environments.get();
    }

    /**
     * Initialize the environment registry. This method is called with <code>createEnvironments</code> set to true once
     * at the startup of the application to create all environments that were created during installation.
     *
     * @param createEnvironments if true, the method will create all environments that are not yet created.
     */
    static void initializeEnvironments(final boolean createEnvironments) {
        synchronized (m_environments) {
            if (m_environments.get() == null) {
                m_environments.set(collectEnvironmentsFromExtensions(createEnvironments));
            }
        }
    }

    /** Loop through extensions and collect them in a Map */
    private static Map<String, CondaEnvironment> collectEnvironmentsFromExtensions(final boolean createEnvironments) {
        final Map<String, CondaEnvironment> environments = new HashMap<>();
        final IExtensionRegistry registry = Platform.getExtensionRegistry();
        final IExtensionPoint point = registry.getExtensionPoint(EXT_POINT_ID);

        var environmentsToInstall = new ArrayList<StartupCreatedEnvPath.MustBeCreated>();

        for (final IExtension ext : point.getExtensions()) {
            try {
                var envExt = CondaEnvironmentExtension.of(ext);
                if (envExt.isEmpty()) {
                    continue; // SKIP - The extension is not valid
                }

                // Phase 1: Check if environment was created during installation (environment_path.txt)
                LOGGER.debugWithFormat("Checking for install-created environment for '%s'",
                    envExt.get().environmentName());
                var installCreatedEnv = findInstallCreatedEnvironment(envExt.get());
                if (installCreatedEnv.isPresent()) {
                    LOGGER.debugWithFormat("Found install-created environment for '%s' at: %s",
                        envExt.get().environmentName(), installCreatedEnv.get().getPath());
                    addIfNotExists(environments, installCreatedEnv.get());

                    continue; // SKIP the rest. The environment was found and added
                }

                // Phase 2: Check if environment was created during startup (metadata.properties)
                LOGGER.debugWithFormat(
                    "No install-created environment found. Checking for startup-created environment for '%s'",
                    envExt.get().environmentName());
                var startupCreatedEnv = findStartupCreatedEnvironment(envExt.get());
                if (startupCreatedEnv instanceof StartupCreatedEnvPath.Exists exists) {
                    // The environment exists and can be used
                    LOGGER.debugWithFormat("Found startup-created environment '%s' at path: %s",
                        exists.environment().getName(), exists.environment().getPath());
                    addIfNotExists(environments, exists.environment());
                } else if (startupCreatedEnv instanceof StartupCreatedEnvPath.MustBeCreated mustBeCreated) {
                    // The environment must be created - this will happen later
                    LOGGER.debugWithFormat("Conda environment '%s' must be created: %s",
                        mustBeCreated.ext().environmentName(), mustBeCreated.message());
                    environmentsToInstall.add(mustBeCreated);
                } else if (startupCreatedEnv instanceof StartupCreatedEnvPath.Failed failed) {
                    // Environment previously failed - ask user what to do
                    var userChoice = askUserForFailedEnvironment(failed);
                    switch (userChoice) {
                        case RETRY -> {
                            // Remove failed metadata and retry installation
                            try {
                                var bundlingRoot = BundlingRoot.getInstance();
                                var environmentRoot = bundlingRoot.getEnvironmentRoot(failed.ext().environmentName());
                                Files.deleteIfExists(
                                    environmentRoot.resolve(StartupCreatedEnvironmentMetadata.METADATA_FILE_NAME));
                                LOGGER.infoWithFormat("User chose to retry installation for environment '%s'",
                                    failed.ext().environmentName());
                                environmentsToInstall.add(new StartupCreatedEnvPath.MustBeCreated(failed.ext(),
                                    "Retrying installation for " + failed.extensionName() + " (user requested retry)"));
                            } catch (IOException e) {
                                LOGGER.error("Failed to delete metadata for retry: " + e.getMessage(), e);
                                // Fall back to skipping for this session
                                LOGGER.infoWithFormat("Skipping environment '%s' for this session due to retry error",
                                    failed.ext().environmentName());
                            }
                        }
                        case SKIP -> {
                            // Skip for this session only - environment remains marked as failed
                            LOGGER.infoWithFormat("User chose to skip environment '%s' for this session",
                                failed.ext().environmentName());
                        }
                        case SKIP_PERMANENTLY -> {
                            // Mark as permanently skipped
                            try {
                                var bundlingRoot = BundlingRoot.getInstance();
                                var environmentRoot = bundlingRoot.getEnvironmentRoot(failed.ext().environmentName());
                                StartupCreatedEnvironmentMetadata.writeSkipped(failed.ext().bundle().getVersion(),
                                    environmentRoot);
                                LOGGER.infoWithFormat("User chose to permanently skip environment '%s'",
                                    failed.ext().environmentName());
                            } catch (IOException e) {
                                LOGGER.error("Failed to write skipped metadata: " + e.getMessage(), e);
                                // Environment remains marked as failed for next time
                            }
                        }
                    }
                } else if (startupCreatedEnv instanceof StartupCreatedEnvPath.Skipped skipped) {
                    // Environment was permanently skipped by user - create a disabled placeholder
                    LOGGER.debugWithFormat(
                        "Environment '%s' is permanently skipped by user, creating disabled placeholder",
                        skipped.ext().environmentName());
                    var disabledEnv = CondaEnvironment.createDisabledPlaceholder(skipped.ext().bundle(),
                        skipped.ext().environmentName());
                    addIfNotExists(environments, disabledEnv);
                }
            } catch (final Exception e) {
                LOGGER.error("An exception occurred while registering an extension at extension point '" + EXT_POINT_ID
                    + "'. Using Python nodes that require the environment will fail.", e);
            }
        }

        if (!environmentsToInstall.isEmpty()) {
            LOGGER.debugWithFormat("Found %d Conda environments that need to be installed.",
                environmentsToInstall.size());
            if (createEnvironments && !SKIP_INSTALL_CONDA_ENVIRONMENT_ON_STARTUP) {
                // NOTE: We ignore the flag here. If the flag was set during extension installation but is not set now,
                // we still need to create the environments to make the extensions work.
                var installedEnvs = installStartupCreatedEnvironments(environmentsToInstall);
                installedEnvs.forEach(env -> addIfNotExists(environments, env));
            } else if (SKIP_INSTALL_CONDA_ENVIRONMENT_ON_STARTUP) {
                LOGGER.infoWithFormat(
                    "Skipping installation of %d Conda environments because skip_install_envs_on_startup is enabled. "
                        + "Python nodes requiring these environments may not work until environments are manually installed.",
                    environmentsToInstall.size());
            } else {
                LOGGER.warnWithFormat(
                    "Found %d Conda environments that are not yet created. They will be created on a restart.",
                    environmentsToInstall.size());
            }
        }

        if (createEnvironments) {
            // Note: We do this even if we did not create any environments but only if we are in the startup phase mode
            cleanupStartupCreatedEnvironments(environments);
        }

        return Collections.unmodifiableMap(environments);
    }

    /** Little utility to add an environment to the map if it is not part of the map already. */
    private static void addIfNotExists(final Map<String, CondaEnvironment> environments, final CondaEnvironment env) {
        if (environments.containsKey(env.getName())) {
            LOGGER.errorWithFormat(
                "An environment with the name '%s' is already registered. Use a unique environment name.",
                env.getName());
        } else {
            environments.put(env.getName(), env);
        }
    }

    /** Extracted information from an extension for the CondaEnvironment extension point. */
    private record CondaEnvironmentExtension(Bundle bundle, String environmentName, Bundle binaryFragment,
            boolean requiresDownload) {

        /** Extract basic information from the extension. */
        private static Optional<CondaEnvironmentExtension> of(final IExtension extension) {
            final String bundleName = extension.getContributor().getName();

            // Get the name of the environment
            final String name = extension.getLabel();
            if (name == null || name.isBlank()) {
                LOGGER.errorWithFormat("The name of the Conda environment defined by the plugin '%s' is missing. "
                    + "Please specify a unique name.", bundleName);
                return Optional.empty();
            }

            // Get the path to the environment
            final var bundle = Platform.getBundle(bundleName);
            final Bundle[] fragments = Platform.getFragments(bundle);

            if (fragments.length < 1) {
                LOGGER.errorWithFormat(
                    "Could not find a platform-specific fragment for the bundled Conda environment in plugin '%s' "
                        + "(operating system: %s, system architecture: %s).",
                    bundle, Platform.getOS(), Platform.getOSArch());
                return Optional.empty();
            }
            if (fragments.length > 1) {
                final String usedFragmentName = fragments[0].getSymbolicName();
                final String unusedFragmentNames =
                    Arrays.stream(fragments).skip(1).map(Bundle::getSymbolicName).collect(Collectors.joining(", "));
                LOGGER.warnWithFormat(
                    "Found %d platform specific fragments for the bundled Conda environment in plugin '%s' "
                        + "(operating system: %s, system architecture: %s). "
                        + "The fragment '%s' will be used. The fragments [%s] will be ignored.",
                    fragments.length, bundleName, Platform.getOS(), Platform.getOSArch(), usedFragmentName,
                    unusedFragmentNames);
            }

            var requiresDownload = Arrays.stream(extension.getConfigurationElements())
                .anyMatch(e -> "requires-download".equals(e.getName()));

            return Optional.of(new CondaEnvironmentExtension(bundle, name, fragments[0], requiresDownload));
        }
    }

    // ================================================================================================================
    // Utilities for startup-created environments
    // ================================================================================================================

    /**
     * Tries to find the path to a Conda environment that was created during startup for the given extension.
     *
     * @return the path to the environment if it exists and is up-to-date with the extension or a message indicating
     *         that and why the environment must be created.
     * @throws IOException if the bundling root cannot be accessed
     */
    private static StartupCreatedEnvPath findStartupCreatedEnvironment(final CondaEnvironmentExtension ext)
        throws IOException {
        var bundlingRoot = BundlingRoot.getInstance();
        var bundleVersion = ext.bundle().getVersion();
        var extensionName = ext.bundle().getSymbolicName();

        var environmentRoot = bundlingRoot.getEnvironmentRoot(ext.environmentName());
        var envMeta = StartupCreatedEnvironmentMetadata.read(environmentRoot);
        if (envMeta.isPresent()) {
            LOGGER.debugWithFormat("Found metadata.properties for '%s' - checking if environment is up-to-date",
                ext.environmentName());
            var meta = envMeta.get();
            if (meta.failed) {
                // Check if user has chosen to skip this environment permanently
                if (meta.skipped) {
                    LOGGER.infoWithFormat(
                        "Skipping environment '%s' because it was marked as permanently skipped by user. This can be undone by deleting the metadata.properties file.",
                        ext.environmentName());
                    return new StartupCreatedEnvPath.Skipped(ext);
                }
                // Environment previously failed - let user decide what to do
                return new StartupCreatedEnvPath.Failed(ext, extensionName);
            } else if ("qualifier".equals(bundleVersion.getQualifier())) {
                // In development - recreate the environment every time
                return new StartupCreatedEnvPath.MustBeCreated(ext, "Recreating Python environment for " + extensionName
                    + " because it is in development mode (qualifier version).");
            } else if (!Objects.equals(meta.version, bundleVersion.toString())) {
                // Environment is not up-to-date - recreate it
                return new StartupCreatedEnvPath.MustBeCreated(ext,
                    "Recreating Python environment for " + extensionName + " because the bundle was updated from "
                        + meta.version + " to " + ext.bundle().getVersion() + ".");
            } else if (!Objects.equals(meta.creationPath, environmentRoot.toAbsolutePath().toString())) {
                // Environment was moved - recreate it
                return new StartupCreatedEnvPath.MustBeCreated(ext,
                    "Recreating Python environment for " + extensionName
                        + " because the conda environments root was moved from " + meta.creationPath + " to "
                        + environmentRoot.toAbsolutePath() + ".");
            } else {
                // Environment is up-to-date
                var path = InstallCondaEnvironment.resolvePixiEnvironmentPath(environmentRoot);
                return new StartupCreatedEnvPath.Exists(
                    new CondaEnvironment(ext.bundle(), path, ext.environmentName(), ext.requiresDownload()));
            }
        }
        LOGGER.infoWithFormat("No metadata.properties found for '%s' at %s - environment needs to be created",
            ext.environmentName(), environmentRoot);
        return new StartupCreatedEnvPath.MustBeCreated(ext,
            "Creating Python environment for " + ext.environmentName() + ".");
    }

    /**
     * User choices for handling failed environment installations.
     */
    private enum UserChoice {
            RETRY, SKIP, SKIP_PERMANENTLY
    }

    /**
     * Asks the user what to do with a failed environment installation.
     *
     * @param failedEnv the failed environment information
     * @return the user's choice
     */
    // Visibility relaxed for test support of headless behavior.
    static UserChoice askUserForFailedEnvironment(final StartupCreatedEnvPath.Failed failedEnv) {
        // If there is no display (headless / warm-start without UI) we cannot ask the user -> retry by default
        var display = Display.getCurrent(); // prefer current; falls back to default below
        if (display == null) { // headless scenario
            LOGGER.infoWithFormat("Headless startup: retrying failed environment '%s' automatically.",
                failedEnv.ext().environmentName());
            return UserChoice.RETRY; // attempt automatic recovery
        }

        final var result = new AtomicReference<>(UserChoice.SKIP);
        display.syncExec(() -> {
            Shell shell = display.getActiveShell();
            boolean disposeShell = false;
            if (shell == null || shell.isDisposed()) {
                // Create a temporary shell if none is active
                shell = new Shell(display);
                disposeShell = true;
            }
            try {
                var dialog = new MessageDialog(shell, "Failed Environment Installation", null,
                    String.format(
                        "The conda environment for %s failed to install previously. What would you like to do?",
                        failedEnv.extensionName()),
                    MessageDialog.QUESTION, new String[]{"Retry Installation", "Skip This Time", "Skip Permanently"},
                    0);

                var choice = dialog.open();
                switch (choice) {
                    case 0 -> result.set(UserChoice.RETRY);
                    case 1 -> result.set(UserChoice.SKIP);
                    case 2 -> result.set(UserChoice.SKIP_PERMANENTLY);
                    default -> result.set(UserChoice.SKIP);
                }
            } finally {
                if (disposeShell && shell != null && !shell.isDisposed()) {
                    shell.dispose();
                }
            }
        });
        return result.get();
    }

    /**
     * Helper used in tests to verify headless retry logic without needing SWT Display.
     * 
     * @param environmentName the name of the failed environment
     * @return {@link UserChoice#RETRY}
     */
    static UserChoice determineUserChoiceForFailedEnvironmentHeadless(final String environmentName) {
        // Mirrors behavior in askUserForFailedEnvironment when Display is null.
        LOGGER.debugWithFormat("[TEST] Headless retry helper invoked for '%s'", environmentName);
        return UserChoice.RETRY;
    }

    /** Return value of {@link #findStartupCreatedEnvironment(CondaEnvironmentExtension)}. */
    private sealed interface StartupCreatedEnvPath permits StartupCreatedEnvPath.Exists,
            StartupCreatedEnvPath.MustBeCreated, StartupCreatedEnvPath.Failed, StartupCreatedEnvPath.Skipped {

        /** Indicates that the environment exists and can be used as is. */
        @SuppressWarnings("javadoc") // it's private
        record Exists(CondaEnvironment environment) implements StartupCreatedEnvPath {
        }

        /** Indicates that the environment must be created. The message is shown to the user. */
        @SuppressWarnings("javadoc") // it's private
        record MustBeCreated(CondaEnvironmentExtension ext, String message) implements StartupCreatedEnvPath {
        }

        /** Indicates that the environment failed and user needs to decide what to do. */
        @SuppressWarnings("javadoc") // it's private
        record Failed(CondaEnvironmentExtension ext, String extensionName) implements StartupCreatedEnvPath {
        }

        /** Indicates that the environment was permanently skipped by user. */
        @SuppressWarnings("javadoc") // it's private
        record Skipped(CondaEnvironmentExtension ext) implements StartupCreatedEnvPath {
        }
    }

    /** Create conda environments. Blocks and shows a progress monitor to the user. */
    private static List<CondaEnvironment>
        installStartupCreatedEnvironments(final List<StartupCreatedEnvPath.MustBeCreated> environmentsToInstall) {

    // Set flag to indicate installation is in progress
    s_environmentInstallationInProgress.set(true);

        record InstallEnvironmentsRunnable( //
                List<StartupCreatedEnvPath.MustBeCreated> envsToInstall, //
                List<CondaEnvironment> installedEnvironments //
        ) implements IRunnableWithProgress {

            @Override
            public void run(final IProgressMonitor progressMonitor)
                throws InvocationTargetException, InterruptedException {
                progressMonitor.beginTask("First start after extension update/installation.", envsToInstall.size());

                for (var i = 0; i < envsToInstall.size(); i++) {
                    // Check if user clicked cancel
                    if (progressMonitor.isCanceled()) {
                        // Write metadata files with failed flag for remaining environments
                        writeFailedMetadataForRemainingEnvironments(i);
                        throw new InterruptedException("Environment installation was cancelled by user");
                    }

                    var mustBeCreated = envsToInstall.get(i);

                    progressMonitor.subTask(mustBeCreated.message + " (" + (i + 1) + "/" + envsToInstall.size() + ").");

                    // Install the environment and get the path
                    var ext = mustBeCreated.ext();
                    try {
                        var envPath = installEnvironment(ext, progressMonitor);
                        installedEnvironments.add(
                            new CondaEnvironment(ext.bundle(), envPath, ext.environmentName(), ext.requiresDownload()));
                    } catch (final Exception ex) { // NOSONAR: we want to catch all exceptions here to add context
                        // Write failed metadata for this environment
                        try {
                            var bundlingRoot = BundlingRoot.getInstance();
                            var environmentRoot = bundlingRoot.getEnvironmentRoot(ext.environmentName());
                            if (!Files.exists(environmentRoot)) {
                                Files.createDirectories(environmentRoot);
                            }
                            StartupCreatedEnvironmentMetadata.writeFailed(ext.bundle().getVersion(), environmentRoot);
                            LOGGER.infoWithFormat("Written failed metadata for environment: %s", ext.environmentName());
                        } catch (final IOException ex2) {
                            LOGGER.error("Failed to write failed metadata for environment " + ext.environmentName(),
                                ex2);
                        }

                        var message = "Failed to create the Python environment for " + ext.bundle().getSymbolicName()
                            + ". Nodes using this environment will not work or show up in the node repository.\n\n";
                        if (ex instanceof InterruptedException) {
                            message += "The operation was interrupted.";
                        } else {
                            message += "Cause: " + ex.getMessage();
                        }
                        throw new InvocationTargetException(ex, message);

                    }
                    progressMonitor.worked(1);
                }
            }

            /**
             * Writes metadata files with failed flag for environments that were not processed when cancellation or
             * failure occurred.
             *
             * @param startIndex the index from which to start writing failed metadata files
             */
            private void writeFailedMetadataForRemainingEnvironments(final int startIndex) {
                for (var i = startIndex; i < envsToInstall.size(); i++) {
                    var mustBeCreated = envsToInstall.get(i);
                    var ext = mustBeCreated.ext();
                    try {
                        var bundlingRoot = BundlingRoot.getInstance();
                        var environmentRoot = bundlingRoot.getEnvironmentRoot(ext.environmentName());

                        // Create the environment root directory if it doesn't exist
                        if (!Files.exists(environmentRoot)) {
                            Files.createDirectories(environmentRoot);
                        }

                        // Write metadata with failed flag
                        StartupCreatedEnvironmentMetadata.writeFailed(ext.bundle().getVersion(), environmentRoot);
                        LOGGER.infoWithFormat("Written failed metadata for environment: %s", ext.environmentName());

                    } catch (final IOException ex2) {
                        LOGGER.error("Failed to write failed metadata for environment " + ext.environmentName(), ex2);
                    }
                }
            }

            private static Path installEnvironment(final CondaEnvironmentExtension ext,
                final IProgressMonitor progressMonitor)
                throws IOException, PixiBinaryLocationException, InterruptedException {
                var bundlingRoot = BundlingRoot.getInstance();

                // Install the environment
                var artifactLocation = FileLocator.getBundleFileLocation(ext.binaryFragment()) //
                    .orElseThrow(() -> new IllegalStateException("The binary fragment could not be located.")) //
                    .toPath() //
                    .toAbsolutePath();
                var environmentRoot = bundlingRoot.getEnvironmentRoot(ext.environmentName());

                // Check for cancellation before starting the long-running pixi operation
                if (progressMonitor.isCanceled()) {
                    throw new InterruptedException("Environment installation was cancelled before pixi install");
                }
                InstallCondaEnvironment.installCondaEnvironment( //
                    artifactLocation, //
                    environmentRoot, //
                    bundlingRoot.getRoot(), //
                    progressMonitor::isCanceled);

                // Create the metadata file next to the environment
                StartupCreatedEnvironmentMetadata.write(ext.bundle().getVersion(), environmentRoot);

                // Also write environment_path.txt so this environment can be found via install-created logic
                // This makes the environment discoverable even if KNIME_PYTHON_BUNDLING_PATH changes
                try {
                    var fragmentLocation = artifactLocation; // artifactLocation is already the fragment path
                    Path environmentPathFile = fragmentLocation.resolve(InstallCondaEnvironment.ENVIRONMENT_PATH_FILE);
                    var installationRoot = fragmentLocation.getParent().getParent();
                    var actualEnvironmentPath = InstallCondaEnvironment.resolvePixiEnvironmentPath(environmentRoot);
                    var relativePath = installationRoot.relativize(actualEnvironmentPath);
                    Files.writeString(environmentPathFile, relativePath.toString(), StandardCharsets.UTF_8);
                    LOGGER.infoWithFormat(
                        "Written environment path file for startup-created environment: %s -> %s (actual environment path)",
                        ext.environmentName(), relativePath);
                } catch (IOException ex) {
                    // Log but don't fail the installation - metadata.properties is sufficient for startup-created envs
                    LOGGER.warn("Failed to write environment_path.txt for " + ext.environmentName()
                        + ", environment will only be discoverable via metadata.properties: " + ex.getMessage());
                }

                return InstallCondaEnvironment.resolvePixiEnvironmentPath(environmentRoot);
            }
        }

        // TODO(AP-24742) This probably does not work during warm start of the docker image (when the UI is not available).
        var activeShell = Display.getDefault().getActiveShell();
        var progressDialog = new ProgressMonitorDialog(activeShell);
        var environmentInstallRunnable =
            new InstallEnvironmentsRunnable(environmentsToInstall, new ArrayList<>(environmentsToInstall.size()));

        try {
            // Note, that this blocks the KNIME AP startup
            progressDialog.run( //
                true, // run the runnable in a separate thread (so that the UI is not blocked)
                true, // make cancelable - check isCanceled() in the runnable
                environmentInstallRunnable //
            );
        } catch (InterruptedException | InvocationTargetException ex) {
            var message = ex.getMessage();
            LOGGER.error(ex);
            MessageDialog.openError(activeShell, "Python environment creation failed", message);

            // Restore the interrupted status
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            s_environmentInstallationInProgress.set(false); // Always reset the flag when installation completes or fails
        }

        return environmentInstallRunnable.installedEnvironments;
    }

    /**
     * Deletes all Conda environments that were created during startup but are not part of the current installation.
     *
     * @param environments the map of all currently registered Conda environments
     */
    private static void cleanupStartupCreatedEnvironments(final Map<String, CondaEnvironment> environments) {
        final BundlingRoot bundlingRoot;
        try {
            bundlingRoot = BundlingRoot.getInstance();
        } catch (IOException ex) {
            LOGGER.error("Failed to get conda environments root: " + ex.getMessage(), ex);
            return; // Cannot clean up if we cannot access the bundling root
        }
        var root = bundlingRoot.getRoot();

        if (Files.notExists(root)) {
            LOGGER.info("Bundling root does not exist: " + root + ". No cleanup needed.");
            return;
        }

        // Collect all directories that contain the registered Conda environments
        var registeredEnvPaths = environments.values().stream() //
            .map(CondaEnvironment::getPath) //
            .filter(path -> path.startsWith(root)) //
            .collect(Collectors.toSet());

        // List all directories in the bundling root and delete those that are not registered
        try (var dirs = Files.list(root)) {
            dirs.filter(path -> Files.isDirectory(path)) //
                .filter(path -> !path.getFileName().toString().startsWith(".")) // ignore the .pixi-cache
                .filter(path -> registeredEnvPaths.stream() //
                    .noneMatch(registeredEnvPath -> registeredEnvPath.startsWith(path)) //
                ) // Filter out directories that contain registered environments
                .filter(path -> { // Don't delete directories that contain failed environment metadata - preserve for user choice
                    try {
                        var metadata = StartupCreatedEnvironmentMetadata.read(path);
                        if (metadata.isPresent() && metadata.get().failed()) {
                            LOGGER.debugWithFormat("Preserving failed environment directory for user choice: %s", path);
                            return false; // Don't delete
                        }
                        return true; // Safe to delete
                    } catch (Exception e) {
                        return true; // If we can't read metadata, err on the side of caution and delete
                    }
                }).forEach(envDir -> {
                    try {
                        PathUtils.deleteDirectory(envDir);
                        LOGGER.info("Deleted unused Conda environment directory: " + envDir);
                    } catch (IOException e) {
                        LOGGER.error("Failed to delete unused Conda environment directory: " + envDir, e);
                    }
                });
        } catch (IOException e) {
            LOGGER.error("Failed to list Conda environment directories for cleanup: " + e.getMessage(), e);
        }
    }

    /**
     * Information about a Conda environment that was created during startup. This information is stored in a
     * "metadata.properties" file next to the environment.
     *
     * @param version      the version of the bundle that created this environment
     * @param creationPath the root path of the environment, where the metadata file is located. This is used to detect
     *                         if an environment was moved by the user.
     * @param failed       whether the environment creation failed (includes cancellation and other errors)
     * @param skipped      whether the user has permanently skipped this environment
     */
    private record StartupCreatedEnvironmentMetadata(String version, String creationPath, boolean failed,
            boolean skipped) {

        private static final String METADATA_FILE_NAME = "metadata.properties";

        /**
         * Parses an env-metadata file and returns an EnvironmentInformation instance.
         */
        static Optional<StartupCreatedEnvironmentMetadata> read(final Path environmentRoot) {
            if (Files.exists(environmentRoot) && Files.isDirectory(environmentRoot)) {
                try (var reader = Files.newBufferedReader(environmentRoot.resolve(METADATA_FILE_NAME))) {
                    var props = new Properties();
                    props.load(reader);
                    var version = props.getProperty("version");
                    var creationPath = props.getProperty("creationPath");
                    // Check for both "failed" and legacy "canceled" properties for backward compatibility
                    var failed = Boolean.parseBoolean(props.getProperty("failed", "false"))
                        || Boolean.parseBoolean(props.getProperty("canceled", "false"));
                    var skipped = Boolean.parseBoolean(props.getProperty("skipped", "false"));
                    return Optional.of(new StartupCreatedEnvironmentMetadata(version, creationPath, failed, skipped));
                } catch (IOException e) {
                    LOGGER.warn("Could not read environment metadata from " + environmentRoot, e);
                    return Optional.empty();
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("Invalid environment metadata in " + environmentRoot, e);
                    return Optional.empty();
                }
            } else {
                return Optional.empty();
            }
        }

        /**
         * Writes this EnvironmentInformation to an env-metadata file.
         *
         * @throws IOException if writing the metadata file fails
         */
        static void write(final Version version, final Path environmentRoot) throws IOException {
            var props = new Properties();
            props.setProperty("version", version.toString());
            props.setProperty("creationPath", environmentRoot.toAbsolutePath().toString());
            try (var writer = Files.newBufferedWriter(environmentRoot.resolve(METADATA_FILE_NAME))) {
                props.store(writer, null);
            }
        }

        /**
         * Writes a metadata file with a failed flag indicating the environment installation failed.
         *
         * @param version         the bundle version
         * @param environmentRoot the root directory for the environment
         * @throws IOException if writing the metadata file fails
         */
        static void writeFailed(final Version version, final Path environmentRoot) throws IOException {
            var props = new Properties();
            props.setProperty("version", version.toString());
            props.setProperty("creationPath", environmentRoot.toAbsolutePath().toString());
            props.setProperty("failed", "true");
            try (var writer = Files.newBufferedWriter(environmentRoot.resolve(METADATA_FILE_NAME))) {
                props.store(writer, null);
            }
        }

        /**
         * Writes a metadata file marking the environment as permanently skipped by the user.
         *
         * @param version         the bundle version
         * @param environmentRoot the root directory for the environment
         * @throws IOException if writing the metadata file fails
         */
        static void writeSkipped(final Version version, final Path environmentRoot) throws IOException {
            var props = new Properties();
            props.setProperty("version", version.toString());
            props.setProperty("creationPath", environmentRoot.toAbsolutePath().toString());
            props.setProperty("failed", "true");
            props.setProperty("skipped", "true");
            try (var writer = Files.newBufferedWriter(environmentRoot.resolve(METADATA_FILE_NAME))) {
                props.store(writer, null);
            }
        }
    }

    // ================================================================================================================
    // Utilities for install-created environments
    // ================================================================================================================

    /**
     * Finds path to the Conda environment that was installed during installation time for the given extension.
     *
     * @return a {@link CondaEnvironment} object with the information about the environment or
     *         <code>Optional.empty()</code> if the environment could not be found because KNIME is configured to
     *         install environments on startup or the environment installation failed.
     *
     */
    private static Optional<CondaEnvironment> findInstallCreatedEnvironment(final CondaEnvironmentExtension extension) {
        Path path = null;

        var bundleName = extension.bundle().getSymbolicName();
        String bundleLocationString =
            FileLocator.getBundleFileLocation(extension.binaryFragment()).orElseThrow().getAbsolutePath();
        Path bundleLocationPath = Paths.get(bundleLocationString);
        Path installationDirectoryPath = bundleLocationPath.getParent().getParent();

        // try to find environment_path.txt, if that is present, use that.
        try {
            Path environmentPathFile =
                CondaEnvironmentBundlingUtils.getAbsolutePath(extension.binaryFragment(), ENVIRONMENT_PATH_FILE);
            String environmentPath = FileUtils.readFileToString(environmentPathFile.toFile(), StandardCharsets.UTF_8);
            environmentPath = environmentPath.trim();
            // Note: if environmentPath is absolute, resolve returns environmentPath directly
            path = installationDirectoryPath.resolve(environmentPath);
            LOGGER.debugWithFormat("Found environment path '%s' (before expansion: '%s') for '%s' in '%s'", path,
                environmentPath, bundleName, environmentPathFile);

        } catch (IOException e) {
            // No problem, we only introduced the environment_path.txt file in 5.4. Trying other env locations...
            LOGGER.debug(
                "No " + ENVIRONMENT_PATH_FILE + " file found for '" + bundleName + "' - checking legacy locations");

            String knimePythonBundlingPath = System.getenv("KNIME_PYTHON_BUNDLING_PATH");
            if (knimePythonBundlingPath != null) {
                path = Paths.get(knimePythonBundlingPath, extension.environmentName());
                LOGGER.debug("KNIME_PYTHON_BUNDLING_PATH is set, expecting environment at '" + path + "' for '"
                    + bundleName + "'");
            } else {
                path = installationDirectoryPath.resolve(BUNDLE_PREFIX).resolve(extension.environmentName());
            }
        }

        if (!Files.exists(path)) {
            try {
                Path envFolderPath =
                    CondaEnvironmentBundlingUtils.getAbsolutePath(extension.binaryFragment(), ENV_FOLDER_NAME);
                if (CondaEnvironmentBundlingUtils.isCondaEnvironment(envFolderPath)) {
                    path = envFolderPath;
                    LOGGER.debug("Found environment for '" + bundleName + "' inside plugin folder: " + path);
                } else {
                    // NOTE: Fragments using pixi-pack (starting with 5.5) also have an env folder, but it is not
                    // a conda environment. For these fragments, we only come here if we are supposed to create the
                    // environment on startup.
                    LOGGER.debug("'env' folder for '" + bundleName
                        + "' is not a conda environment - will check for startup-created environment");
                    return Optional.empty();
                }
            } catch (final IOException ex) {
                // NOTE: We will attempt to create the environment on startup. This will fail, because it requires the
                // 'env' folder to be present. The failure will be shown to the user then. However, we still keep this
                // as an error in the log, so that we can identify the problem if it occurs.
                LOGGER.error(String.format("Could not find the path to the Conda environment for the plugin '%s'. "
                    + "Did the installation of the plugin fail?", bundleName), ex);
                return Optional.empty();
            }
        }

        // If the path points to a pixi environment root (contains pixi.toml), adjust to point to the actual environment
        if (Files.exists(path.resolve("pixi.toml"))) {
            path = InstallCondaEnvironment.resolvePixiEnvironmentPath(path);
            LOGGER.debugWithFormat("Adjusted pixi environment path to: %s for '%s'", path, bundleName);
        } else {
            LOGGER.debugWithFormat("No pixi.toml found at %s, using path as-is for '%s'", path, bundleName);
        }

        return Optional.of(
            new CondaEnvironment(extension.bundle(), path, extension.environmentName(), extension.requiresDownload()));
    }
}
