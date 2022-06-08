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
 *   Feb 2, 2019 (marcel): created
 */
package org.knime.conda;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.SystemUtils;
import org.knime.conda.Conda.CondaEnvironmentChangeListener.ChangeEvent;
import org.knime.conda.prefs.CondaPreferences;
import org.knime.core.node.NodeLogger;
import org.knime.core.util.FileUtil;
import org.knime.core.util.PathUtils;
import org.knime.core.util.Version;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * Interface to an external Conda installation.
 *
 * @author Marcel Wiedenmann, KNIME GmbH, Konstanz, Germany
 * @author Christian Dietz, KNIME GmbH, Konstanz, Germany
 * @author Benjamin Wilhelm, KNIME GmbH, Konstanz, Germany
 */
public final class Conda {

    /**
     * The name of the Conda root/base environment.
     */
    public static final String ROOT_ENVIRONMENT_NAME = "base";

    private static final NodeLogger LOGGER = NodeLogger.getLogger(Conda.class);

    private static final Version CONDA_MINIMUM_VERSION = new Version(4, 6, 2);

    private static final Version CONDA_ENV_EXPORT_FROM_HISTORY_MINIMUM_VERSION = new Version(4, 7, 12);

    private static final String ROOT_ENVIRONMENT_LEGACY_NAME = "root";

    private static final String JSON = "--json";

    private static final Pattern CHANNEL_SEPARATOR = Pattern.compile("::");

    private static final Pattern VERSION_BUILD_SEPARATOR = Pattern.compile("=");

    private static final Set<CondaEnvironmentChangeListener> ENV_CHANGE_LISTENERS = new HashSet<>();

    /**
     * Converts a version string of the form "conda &ltmajor&gt.&ltminor&gt.&ltmicro&gt" into a {@link Version} object.
     *
     * @param condaVersionString The version string.
     * @return The parsed version.
     * @throws IllegalArgumentException If the version string cannot be parsed or produces an invalid version.
     */
    public static Version condaVersionStringToVersion(String condaVersionString) {
        try {
            condaVersionString = condaVersionString.split(" ")[1].trim();
            return new Version(condaVersionString);
        } catch (final Exception ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
    }

    /**
     * Register an {@link CondaEnvironmentChangeListener} that is notified if a conda environment has changed.
     *
     * @param listener
     */
    public static synchronized void registerEnvironmentChangeListener(final CondaEnvironmentChangeListener listener) {
        ENV_CHANGE_LISTENERS.add(listener);
    }

    /**
     * Remove an {@link CondaEnvironmentChangeListener} that was registered with
     * {@link #registerEnvironmentChangeListener(CondaEnvironmentChangeListener)} before.
     *
     * @param listener
     */
    public static synchronized void removeEnvironmentChangeListener(final CondaEnvironmentChangeListener listener) {
        ENV_CHANGE_LISTENERS.remove(listener);
    }

    /** Notify the listeners that the environment has changed */
    private static synchronized void notifyEnvironmentChanged(final ChangeEvent event) {
        for (var l : ENV_CHANGE_LISTENERS) {
            l.environmentChanged(event);
        }
    }

    /** The interface for a listener that is notified if environments are deleted or overwritten. */
    @FunctionalInterface
    public static interface CondaEnvironmentChangeListener {

        /**
         * Called if the environment with the given name has been changed by any instance of the {@link Conda} class.
         * This means that the environment has been overwritten, deleted or the packages in the environment changed.
         *
         * @param event a {@link ChangeEvent} object that describes the change of a Conda environment
         */
        void environmentChanged(final ChangeEvent event);

        /** An event describing the change of a Conda environment. */
        public static class ChangeEvent {

            // NB: We could add attributes to the change event if we
            // want to describe the change better in the future.

            private final String m_envName;

            private ChangeEvent(final String envName) {
                m_envName = envName;
            }

            /** @return the name of the environment that was changed */
            public String getEnvName() {
                return m_envName;
            }
        }
    }

    /**
     * Path to the Conda executable.
     */
    private final String m_executable;

    /**
     * Lazily initialized by {@link #getEnvironments()}.
     */
    private String m_rootPrefix = null;

    /**
     * Creates an interface to the Conda installation configured on the Conda preference page.
     *
     * Use {@link #testInstallation()} to test the validity and the functioning of the installation.
     *
     * @throws IOException If the configured directory does not point to a valid and functioning Conda installation.
     *             This includes cases where the configured directory or any relevant files within that directory cannot
     *             be read (and/or possibly executed) by this application.
     */
    public Conda() throws IOException {
        this(CondaPreferences.getCondaInstallationDirectory());
    }

    /**
     * Creates an interface to the given Conda installation.
     *
     * Use {@link #testInstallation()} to test the validity and the functioning of the installation.
     *
     * @param condaInstallationDirectoryPath The path to the root directory of the Conda installation.
     * @throws IOException If the given directory does not point to a valid and functioning Conda installation. This
     *             includes cases where the given directory or any relevant files within that directory cannot be read
     *             (and/or possibly executed) by this application.
     */
    public Conda(String condaInstallationDirectoryPath) throws IOException {
        final File directoryFile = resolveToInstallationDirectoryFile(condaInstallationDirectoryPath);
        try {
            condaInstallationDirectoryPath = directoryFile.getCanonicalPath();
        } catch (final SecurityException ex) { // NOSONAR We really do not care whether this works.
            // Stick with the unresolved path.
            condaInstallationDirectoryPath = directoryFile.getPath();
        }
        m_executable = getExecutableFromInstallationDirectoryForOS(condaInstallationDirectoryPath);
    }

    private static File resolveToInstallationDirectoryFile(final String installationDirectoryPath) throws IOException {
        final File installationDirectory = new File(installationDirectoryPath);
        try {
            if (!installationDirectory.exists()) {
                throw new IOException("The directory at the given path does not exist.\nPlease specify the path to "
                    + "the directory of your local Conda installation.");
            }
            if (!installationDirectory.isDirectory()) {
                throw new IOException("The given path does not point to a directory.\nPlease point to the root "
                    + "directory of your local Conda installation.");
            }
        } catch (final SecurityException ex) {
            final String errorMessage = "The directory at the given path cannot be read. Please make sure KNIME has "
                + "the proper access rights for the directory and retry.";
            throw new IOException(errorMessage, ex);
        }
        return installationDirectory;
    }

    private static String getExecutableFromInstallationDirectoryForOS(final String installationDirectoryPath)
        throws IOException {
        String[] relativePathToExecutableSegments;
        if (SystemUtils.IS_OS_LINUX || SystemUtils.IS_OS_MAC) {
            relativePathToExecutableSegments = new String[]{"bin", "conda"};
        } else if (SystemUtils.IS_OS_WINDOWS) {
            relativePathToExecutableSegments = new String[]{"condabin", "conda.bat"};
        } else {
            throw createUnknownOSException();
        }
        Path executablePath;
        try {
            executablePath = resolveToExecutablePath(installationDirectoryPath, relativePathToExecutableSegments);
        } catch (final IOException ex) {
            if (SystemUtils.IS_OS_WINDOWS) {
                // Legacy support on Windows. Older installations of Conda don't have "condabin/conda.bat". We won't
                // support such versions (cf. #testInstallation()) but still want to be able to resolve them. This
                // allows us to print a more precise error message ("wrong version installed" vs. "not installed at
                // all").
                relativePathToExecutableSegments = new String[]{"Scripts", "conda.exe"};
                executablePath = resolveToExecutablePath(installationDirectoryPath, relativePathToExecutableSegments);
            } else {
                throw ex;
            }
        }
        return executablePath.toString();
    }

    private static Path resolveToExecutablePath(final String installationDirectoryPath,
        final String[] relativePathToExecutableSegments) throws IOException {
        final Path executablePath;
        try {
            executablePath = Paths.get(installationDirectoryPath, relativePathToExecutableSegments);
        } catch (final InvalidPathException ex) {
            final String errorMessage = ex.getMessage() + "\nThis is an implementation error.";
            throw new IOException(errorMessage, ex);
        }
        try {
            if (!executablePath.toFile().exists()) {
                LOGGER
                    .debug("Specified Conda executable at '" + executablePath.toFile().getPath() + "' does not exist.");
                throw new IOException("The given path does not point to a valid Conda installation.\nPlease point to "
                    + "the root directory of your local Conda installation.");
            }
        } catch (final UnsupportedOperationException ex) {
            // Skip test.
        }
        return executablePath;
    }

    /**
     * @return An {@link UnsupportedOperationException} stating that the local operating system could not be detected or
     *         is not supported.
     */
    public static UnsupportedOperationException createUnknownOSException() {
        final String osName = SystemUtils.OS_NAME;
        if (osName == null) {
            throw new UnsupportedOperationException(
                "Could not detect your operating system. This is necessary for Conda environment generation and use. "
                    + "Please make sure KNIME has the proper access rights to your system.");
        } else {
            throw new UnsupportedOperationException(
                "Conda environment generation and use is only supported on Windows, Mac, and Linux. Your operating "
                    + "system is: " + SystemUtils.OS_NAME);
        }
    }

    /**
     * Test Conda installation by trying to get its version. Method throws an exception if Conda could not be called
     * properly. We also check the version bound since we currently require Conda {@link #CONDA_MINIMUM_VERSION} or
     * later.
     *
     * @throws IOException If the installation test failed.
     */
    public void testInstallation() throws IOException {
        String versionString = getVersionString();
        final Version version;
        try {
            version = condaVersionStringToVersion(versionString);
        } catch (final Exception ex) {
            // Skip test if we can't identify version.
            LOGGER.warn("Could not detect installed Conda version. Please note that a " + "minimum version of "
                + CONDA_MINIMUM_VERSION + " is required.", ex);
            return;
        }
        if (version.compareTo(CONDA_MINIMUM_VERSION) < 0) {
            // Root environment name differs between older and newer versions of Conda.
            final String rootEnvironmentName =
                version.compareTo(new Version(4, 4, 0)) < 0 ? ROOT_ENVIRONMENT_LEGACY_NAME : ROOT_ENVIRONMENT_NAME;
            throw new IOException("Conda version is " + version.toString() + ". Required minimum version is "
                + CONDA_MINIMUM_VERSION + ".\nPlease update Conda (e.g., by executing \"conda update -n "
                + rootEnvironmentName + " conda\" in a terminal) and retry.");
        }
    }

    /**
     * {@code conda --version}
     *
     * @return The raw output of the corresponding Conda command.
     * @throws IOException If an error occurs during execution of the underlying command.
     * @see #condaVersionStringToVersion(String)
     */
    public String getVersionString() throws IOException {
        final AtomicReference<String> version = new AtomicReference<>();
        callCondaAndAwaitTermination(new CondaExecutionMonitor(true) {

            @Override
            protected void handleCustomNonJsonOutput(final String output) {
                version.set(output);
            }
        }, "--version");
        return version.get();
    }

    /**
     * {@code conda env list}
     *
     * @return The descriptions of the existing Conda environments.
     * @throws IOException If an error occurs during execution of the underlying command.
     */
    public List<CondaEnvironmentIdentifier> getEnvironments() throws IOException {
        if (m_rootPrefix == null) {
            m_rootPrefix = getRootPrefix();
        }
        final List<CondaEnvironmentIdentifier> environments = new ArrayList<>();
        callCondaAndAwaitTermination(new CondaExecutionMonitor(true) {

            @Override
            protected void handleCustomJsonOutput(final TreeNode json) {
                final ArrayNode environmentsJson = (ArrayNode)json.get("envs");
                for (int i = 0; i < environmentsJson.size(); i++) {
                    final String environmentPath = environmentsJson.get(i).textValue();
                    final String environmentName;
                    if (environmentPath.equals(m_rootPrefix)) {
                        environmentName = ROOT_ENVIRONMENT_NAME;
                    } else {
                        environmentName = new File(environmentPath).getName();
                    }
                    environments.add(new CondaEnvironmentIdentifier(environmentName, environmentPath));
                }
            }
        }, "env", "list", JSON);
        return environments;

    }

    /**
     * Shortcut for calling {@link #getEnvironments()} and extracting only the names from the list of environments.
     *
     * @return The names of the existing Conda environments.
     * @throws IOException If an error occurs during execution of the underlying command.
     */
    public List<String> getEnvironmentNames() throws IOException {
        return getEnvironments() //
            .stream() //
            .map(CondaEnvironmentIdentifier::getName) //
            .collect(Collectors.toList());
    }

    private String getRootPrefix() throws IOException {
        final AtomicReference<String> rootPrefix = new AtomicReference<>();
        callCondaAndAwaitTermination(new CondaExecutionMonitor(true) {

            @Override
            protected void handleCustomJsonOutput(final TreeNode json) {
                rootPrefix.set(((JsonNode)json.get("root_prefix")).textValue());
            }
        }, "info", JSON);
        return rootPrefix.get();
    }

    /**
     * {@code conda list --name <environmentName>}
     *
     * @param environmentName The name of the environment whose packages to return.
     * @return The packages contained in the environment.
     * @throws IOException If an error occurs during execution of the underlying Conda command.
     */
    public List<CondaPackageSpec> getPackages(final String environmentName) throws IOException {
        final List<CondaPackageSpec> packages = new ArrayList<>();
        callCondaAndAwaitTermination(new CondaExecutionMonitor(true) {

            @Override
            protected void handleCustomJsonOutput(final TreeNode json) {
                final ArrayNode packagesJson = (ArrayNode)json;
                for (int i = 0; i < packagesJson.size(); i++) {
                    final JsonNode packageJson = packagesJson.get(i);
                    final String name = packageJson.get("name").textValue();
                    final String version = packageJson.get("version").textValue();
                    final String build = packageJson.get("build_string").textValue();
                    final String channel = packageJson.get("channel").textValue();
                    packages.add(new CondaPackageSpec(name, version, build, channel));
                }
            }
        }, "list", "--name", environmentName, JSON);
        return packages;
    }

    /**
     * {@code conda env export --name <environmentName> --from-history} is only available from Conda 4.7.12 onward. This
     * method determines whether the corresponding method in this class -- {@link #getPackageNamesFromHistory(String)}
     * -- can safely be invoked.
     *
     * @return {@code true} if {@link #getPackageNamesFromHistory(String)} is available, {@code false} otherwise (also
     *         if determining the Conda version failed).
     */
    public boolean isPackageNamesFromHistoryAvailable() {
        try {
            final String versionString = getVersionString();
            final Version version = condaVersionStringToVersion(versionString);
            return version.compareTo(CONDA_ENV_EXPORT_FROM_HISTORY_MINIMUM_VERSION) >= 0;
        } catch (final Exception ex) {
            LOGGER.debug("Could not detect installed Conda version.", ex);
            return false;
        }
    }

    /**
     * {@code conda env export --name <environmentName> --from-history}
     * <P>
     * Channel and build/version affixes are stripped from the raw output of the command.
     * <P>
     * Note that this method is only available from Conda 4.7.12 onward, see
     * {@link #isPackageNamesFromHistoryAvailable()}.
     *
     * @param environmentName The name of the environment whose packages to return.
     * @return The names of the explicitly installed packages contained in the environment.
     * @throws IOException If an error occurs during execution of the underlying Conda command.
     */
    public List<String> getPackageNamesFromHistory(final String environmentName) throws IOException {
        final List<String> packageNames = new ArrayList<>();
        callCondaAndAwaitTermination(new CondaExecutionMonitor(true) {

            @Override
            protected void handleCustomJsonOutput(final TreeNode json) {
                final ArrayNode packagesJson = (ArrayNode)json.get("dependencies");
                for (int i = 0; i < packagesJson.size(); i++) {
                    String name = packagesJson.get(i).textValue();
                    final String[] splitChannel = CHANNEL_SEPARATOR.split(name, 2);
                    name = splitChannel[splitChannel.length - 1];
                    final String[] splitVersionAndBuild = VERSION_BUILD_SEPARATOR.split(name, 2);
                    name = splitVersionAndBuild[0];
                    packageNames.add(name);
                }
            }
        }, "env", "export", "--name", environmentName, "--from-history", JSON);
        return packageNames;
    }

    /**
     * {@code conda env create --file <pathToFile> --name <environmentName or generated name>}.<br>
     * If environmentName is {@code null} or empty the environment name specified in the file is used.
     *
     * @param pathToFile The path to the environment description file.
     * @param environmentName The name of the environment. Must not already exist in this Conda installation. May be
     *            {@code null} or empty in which case a name from the environment description file is used.
     * @param monitor Receives progress of the creation process. Allows to cancel the environment creation from within
     *            another thread.
     * @return A description of the created environment.
     * @throws IOException If an error occurs during execution of the underlying command.
     * @throws CondaCanceledExecutionException If environment creation was canceled via the given monitor.
     */
    public CondaEnvironmentIdentifier createEnvironmentFromFile(final String pathToFile, final String environmentName,
        final CondaEnvironmentCreationMonitor monitor) throws CondaCanceledExecutionException, IOException {
        IOException failure = null;
        try {
            createEnvironmentFromFile(pathToFile, environmentName, false, monitor);
        } catch (IOException ex) {
            failure = ex;
        }

        // Check if environment creation was successful. Fail if not.
        final List<CondaEnvironmentIdentifier> environments = getEnvironments();
        for (final CondaEnvironmentIdentifier environment : environments) {
            if (Objects.equals(environmentName, environment.getName())) {
                return environment;
            }
        }
        if (failure == null) {
            failure = new IOException("Failed to create Conda environment.");
        }
        throw failure;
    }

    /**
     * {@code conda env create --file <file generated from arguments> --name <environmentName> --force} Overwrites
     * environment {@code <environmentName>} if it already exists.
     *
     * @param environmentName The name of the environment to create. If an environment with the given name already
     *            exists in this Conda installation, it will be overwritten.
     * @param packages The packages to install.
     * @param includeBuildSpecs If {@code true}, the packages' {@link CondaPackageSpec#getBuild() build specs} are
     *            respected/enforced during environment creation, otherwise they are ignored.
     * @param monitor Receives progress of the creation process. Allows to cancel the environment creation from within
     *            another thread.
     * @return The environment.yml file of the created environment. Note that this is a temporary file that is deleted
     *         when the JVM is shut down. Manually copy it if you want to preserve it.
     * @throws IOException If an error occurs during execution of the underlying command.
     * @throws PythonCanceledExecutionException If environment creation was canceled via the given monitor.
     */
    public File createEnvironment(final String environmentName, final List<CondaPackageSpec> packages,
        final boolean includeBuildSpecs, final CondaEnvironmentCreationMonitor monitor)
        throws CondaCanceledExecutionException, IOException {
        final List<CondaPackageSpec> installedByPip = new ArrayList<>();
        final List<CondaPackageSpec> installedByConda = new ArrayList<>();
        final boolean pipExists = filterPipInstalledPackages(packages, installedByPip, installedByConda);

        final List<Object> dependencies = installedByConda.stream() //
            .map(pkg -> {
                String dependency = pkg.getChannel() + "::" + pkg.getName() + "=" + pkg.getVersion();
                if (includeBuildSpecs) {
                    dependency += "=" + pkg.getBuild();
                }
                return dependency;
            }) //
            .collect(Collectors.toList());

        if (!installedByPip.isEmpty()) {
            if (!pipExists) {
                throw new IllegalArgumentException("There are packages in the environment that are to be installed "
                    + "using pip. Therefore you also need to include package 'pip'.");
            }
            final List<String> pipDependencies = installedByPip.stream() //
                .map(p -> p.getName() + "==" + p.getVersion()) //
                .collect(Collectors.toList());
            final Map<String, Object> pip = new LinkedHashMap<>();
            pip.put("pip", pipDependencies);
            dependencies.add(pip);
        }

        final Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("name", environmentName);
        entries.put("dependencies", dependencies);

        final File environmentFile = FileUtil.createTempFile("environment_", ".yml");
        final DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(3);
        options.setIndicatorIndent(2);
        final Yaml yaml = new Yaml(options);
        try (final Writer writer =
            new OutputStreamWriter(new FileOutputStream(environmentFile), StandardCharsets.UTF_8)) {
            yaml.dump(entries, writer);
        }

        try {
            createEnvironmentFromFile(environmentFile.getPath(), null, true, monitor);
        } finally {
            // Notify listeners that the environment has changed
            notifyEnvironmentChanged(new ChangeEvent(environmentName));
        }
        return environmentFile;
    }

    /**
     * {@code conda env create --file <pathToFile> [--name <optionalEnvironmentName>] [--force]}
     * </p>
     * NOTE: This method does not call {@link #notifyEnvironmentChanged(String)}. Please call it afterwards if an
     * environment was changed.
     */
    private void createEnvironmentFromFile(final String pathToFile, final String optionalEnvironmentName,
        final boolean overwriteExistingEnvironment, final CondaEnvironmentCreationMonitor monitor)
        throws CondaCanceledExecutionException, IOException {
        final List<String> arguments = new ArrayList<>(6);
        Collections.addAll(arguments, "env", "create", "--file", pathToFile);
        if (optionalEnvironmentName != null) {
            Collections.addAll(arguments, "--name", optionalEnvironmentName);
        }
        if (overwriteExistingEnvironment) {
            arguments.add("--force");
        }
        arguments.add(JSON);
        callCondaAndMonitorExecution(monitor, arguments.toArray(new String[0]));
    }

    /**
     * Delete the conda environment with the given name. E.g. {@code conda env remove -n <name>}. The operation will not
     * be canceled if the thread is interrupted. After the conda command the directory on the file system will be
     * deleted if it still exists.
     *
     * @param environmentName the name of the environment
     * @throws IOException if running the command failed.
     */
    public void deleteEnvironment(final String environmentName) throws IOException {
        try {
            try {
                // Try the correct way: call conda env remove -n <env_name>
                callCondaAndAwaitTermination(new CondaExecutionMonitor() {
                    @Override
                    protected synchronized boolean isCanceledOrInterrupted() {
                        return false;
                    }
                }, "env", "remove", "-n", environmentName);
            } catch (final Exception ex) {
                LOGGER.warn("Could not delete the incomplete environment using 'conda env remove -n <env_name>'. "
                    + "The environment will still be deleted by deleting the directory.", ex);
            } finally {
                // Try deleting the directory of the environment (on Windows it still exists after conda env remove)
                // Note: Using the first value of the envs_dirs list is correct:
                //
                // $ conda config --describe envs_dirs                                                                                                                                                                                               (bug/AP-16688-conda-cleanup-after-cancel-on-windows *$)
                // # # envs_dirs (sequence: primitive)
                // # #   aliases: envs_path
                // # #   env var string delimiter: ':'
                // # #   The list of directories to search for named environments. When
                // # #   creating a new named environment, the environment will be placed in
                // # #   the first writable location.
                // # #
                // # envs_dirs: []

                final var envPath = Path.of(getEnvsDirs().get(0), environmentName);
                PathUtils.deleteDirectoryIfExists(envPath);
            }
        } finally {
            // After everything: Notify that the environment has changed
            notifyEnvironmentChanged(new ChangeEvent(environmentName));
        }
    }

    /**
     * Get the directories in which environments are located. E.g. {@code conda config --show envs_dirs}. The first
     * directory is used when {@code conda create} is called with an environment name.
     *
     * @return the list of directories in which conda environments are located
     * @throws IOException if running the command failed.
     */
    public List<String> getEnvsDirs() throws IOException {
        final List<String> envsDirs = new ArrayList<>();
        callCondaAndAwaitTermination(new CondaExecutionMonitor(true) {
            @Override
            void handleCustomJsonOutput(final TreeNode json) {
                final var envsDirsJson = (ArrayNode)json.get("envs_dirs");
                for (var i = 0; i < envsDirsJson.size(); i++) {
                    envsDirs.add(envsDirsJson.get(i).textValue());
                }
            }
        }, "config", "--show", "envs_dirs", JSON);
        return envsDirs;
    }

    /**
     * Traverses {@code packages} and adds each package either to {@code outInstalledByPip} or to
     * {@code outInstalledByConda} depending on its source channel. Also returns whether package {@code pip} is
     * contained in {@code packages}.
     *
     * @param packages The packages to filter.
     * @param outInstalledByPip Will be populated with the packages installed via pip (i.e., from a PyPI channel).
     * @param outInstalledByConda Will be populated with the packages installed via conda (i.e., from a Conda channel).
     * @return {@code true} if {@code pip} is contained in the given packages, {@code false} otherwise.
     */
    public static boolean filterPipInstalledPackages(final List<CondaPackageSpec> packages,
        final List<CondaPackageSpec> outInstalledByPip, final List<CondaPackageSpec> outInstalledByConda) {
        boolean pipExists = false;
        for (final CondaPackageSpec pkg : packages) {
            if (!pipExists && "pip".equals(pkg.getName())) {
                pipExists = true;
            }
            if ("pypi".equals(pkg.getChannel())) {
                if (outInstalledByPip != null) {
                    outInstalledByPip.add(pkg);
                }
            } else {
                if (outInstalledByConda != null) {
                    outInstalledByConda.add(pkg);
                }
            }
        }
        return pipExists;
    }

    private void callCondaAndAwaitTermination(final CondaExecutionMonitor monitor, final String... arguments)
        throws IOException {
        try {
            callCondaAndMonitorExecution(monitor, arguments);
        } catch (final CondaCanceledExecutionException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
    }

    private void callCondaAndMonitorExecution(final CondaExecutionMonitor monitor, final String... arguments)
        throws CondaCanceledExecutionException, IOException {
        final boolean hasJsonOutput = Arrays.asList(arguments).contains(JSON);
        final Process conda = startCondaProcess(arguments);
        try {
            monitor.monitorExecution(conda, hasJsonOutput);
        } finally {
            conda.destroy(); // Should not be necessary, but let's play safe here.
        }
    }

    private Process startCondaProcess(final String... arguments) throws IOException {
        final List<String> argumentList = new ArrayList<>(1 + arguments.length);
        argumentList.add(m_executable);
        Collections.addAll(argumentList, arguments);
        final ProcessBuilder pb = new ProcessBuilder(argumentList);
        return pb.start();
    }
}
