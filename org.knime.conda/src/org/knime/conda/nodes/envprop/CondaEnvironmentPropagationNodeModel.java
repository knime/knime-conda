/*
 * ------------------------------------------------------------------------
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
 * ------------------------------------------------------------------------
 *
 * History
 *   Sep 25, 2014 (Patrick Winter): created
 */
package org.knime.conda.nodes.envprop;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import org.apache.commons.lang3.SystemUtils;
import org.knime.conda.Conda;
import org.knime.conda.CondaCanceledExecutionException;
import org.knime.conda.CondaEnvironmentCreationMonitor;
import org.knime.conda.CondaEnvironmentIdentifier;
import org.knime.conda.CondaEnvironmentPropagation;
import org.knime.conda.CondaEnvironmentPropagation.CondaEnvironmentSpec;
import org.knime.conda.CondaEnvironmentPropagation.CondaEnvironmentType;
import org.knime.conda.CondaPackageSpec;
import org.knime.conda.nodes.envprop.CondaEnvironmentPropagationNodeFactory.DefaultCondaEnvironmentSelector;
import org.knime.conda.prefs.CondaPreferences;
import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.ExecutionContext;
import org.knime.core.node.ExecutionMonitor;
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.KNIMEException;
import org.knime.core.node.NodeLogger;
import org.knime.core.node.NodeModel;
import org.knime.core.node.NodeProgressMonitor;
import org.knime.core.node.NodeSettingsRO;
import org.knime.core.node.NodeSettingsWO;
import org.knime.core.node.defaultnodesettings.SettingsModelBoolean;
import org.knime.core.node.defaultnodesettings.SettingsModelString;
import org.knime.core.node.port.PortObject;
import org.knime.core.node.port.PortObjectSpec;
import org.knime.core.node.port.PortType;
import org.knime.core.node.port.flowvariable.FlowVariablePortObject;
import org.knime.core.node.port.flowvariable.FlowVariablePortObjectSpec;
import org.knime.core.node.workflow.NodeContext;
import org.knime.core.util.Pair;
import org.knime.core.util.PathUtils;

/**
 * @author Marcel Wiedenmann, KNIME GmbH, Konstanz, Germany
 */
final class CondaEnvironmentPropagationNodeModel extends NodeModel {

    private static final NodeLogger LOGGER = NodeLogger.getLogger(CondaEnvironmentPropagationNodeModel.class);

    static final String CFG_KEY_CONDA_ENV = "conda_environment";

    static final String CFG_KEY_ENV_VALIDATION_METHOD = "environment_validation";

    static final String VALIDATION_METHOD_NAME = "name";

    static final String VALIDATION_METHOD_NAME_PACKAGES = "name_packages";

    static final String VALIDATION_METHOD_OVERWRITE = "overwrite";

    static final String CFG_KEY_OUTPUT_VARIABLE_NAME = "output_variable_name";

    static final String CFG_KEY_SOURCE_OS_NAME = "source_operating_system";

    static final String CFG_KEY_PRESERVE_INCOMPLETE_ENVS = "preserve_incomplete_environments";

    private static final String OS_LINUX = "linux";

    private static final String OS_MAC = "mac";

    private static final String OS_WINDOWS = "windows";

    /**
     * Lazily populated upon the first execution of an instance of this class.
     */
    private static /* final */ PlatformCondaPackageFilter platformPackageFilter;

    static final List<DefaultCondaEnvironmentSelector> DEFAULT_ENV_SELECTORS = new ArrayList<>(1);

    static SettingsModelString createCondaEnvironmentNameModel() {
        return new SettingsModelString(CFG_KEY_CONDA_ENV, CondaEnvironmentIdentifier.PLACEHOLDER_CONDA_ENV.getName());
    }

    static CondaPackagesConfig createPackagesConfig() {
        return new CondaPackagesConfig();
    }

    static String[] createEnvironmentValidationMethodKeys() {
        return new String[]{VALIDATION_METHOD_NAME, VALIDATION_METHOD_NAME_PACKAGES, VALIDATION_METHOD_OVERWRITE};
    }

    static SettingsModelString createEnvironmentValidationMethodModel() {
        return new SettingsModelString(CFG_KEY_ENV_VALIDATION_METHOD, createEnvironmentValidationMethodKeys()[0]);
    }

    static SettingsModelString createOutputVariableNameModel() {
        return new SettingsModelString(CFG_KEY_OUTPUT_VARIABLE_NAME,
            CondaEnvironmentPropagation.DEFAULT_ENV_FLOW_VAR_NAME);
    }

    static SettingsModelBoolean createPreserveIncompleteEnvsModel() {
        return new SettingsModelBoolean(CFG_KEY_PRESERVE_INCOMPLETE_ENVS, false);
    }

    static SettingsModelString createSourceOsModel() {
        return new SettingsModelString(CFG_KEY_SOURCE_OS_NAME, getCurrentOsType());
    }

    private final SettingsModelString m_environmentNameModel = createCondaEnvironmentNameModel();

    private final CondaPackagesConfig m_packagesConfig = createPackagesConfig();

    private final SettingsModelString m_validationMethodModel = createEnvironmentValidationMethodModel();

    private final SettingsModelString m_outputVariableNameModel = createOutputVariableNameModel();

    private final SettingsModelBoolean m_preserveIncompleteEnvsModel = createPreserveIncompleteEnvsModel();

    private final SettingsModelString m_sourceOsModel = createSourceOsModel();

    public CondaEnvironmentPropagationNodeModel() {
        super(new PortType[0], new PortType[]{FlowVariablePortObject.TYPE});
    }

    @Override
    protected void saveSettingsTo(final NodeSettingsWO settings) {
        m_environmentNameModel.saveSettingsTo(settings);
        m_packagesConfig.saveSettingsTo(settings);
        m_validationMethodModel.saveSettingsTo(settings);
        m_outputVariableNameModel.saveSettingsTo(settings);
        m_preserveIncompleteEnvsModel.saveSettingsTo(settings);
        m_sourceOsModel.saveSettingsTo(settings);
    }

    @Override
    protected void validateSettings(final NodeSettingsRO settings) throws InvalidSettingsException {
        m_environmentNameModel.validateSettings(settings);
        CondaPackagesConfig.validateSettings(settings);
        m_validationMethodModel.validateSettings(settings);
        // Backward compatibility: environment variable name model was introduced in KNIME 4.4; only attempt to validate
        // it if present.
        if (settings.containsKey(m_outputVariableNameModel.getKey())) {
            m_outputVariableNameModel.validateSettings(settings);
        }
        if (settings.containsKey(m_preserveIncompleteEnvsModel.getConfigName())) {
            m_preserveIncompleteEnvsModel.validateSettings(settings);
        }
        m_sourceOsModel.validateSettings(settings);
    }

    @Override
    protected void loadValidatedSettingsFrom(final NodeSettingsRO settings) throws InvalidSettingsException {
        m_environmentNameModel.loadSettingsFrom(settings);
        m_packagesConfig.loadSettingsFrom(settings);
        m_validationMethodModel.loadSettingsFrom(settings);
        // Backward compatibility: output variable name model was introduced in KNIME 4.4; only attempt to load it
        // if present.
        if (settings.containsKey(m_outputVariableNameModel.getKey())) {
            m_outputVariableNameModel.loadSettingsFrom(settings);
            final String outputVariableName = m_outputVariableNameModel.getStringValue();
            if (outputVariableName == null || outputVariableName.isBlank()) {
                throw new InvalidSettingsException("The output variable name may not be blank.");
            }
        }
        if (settings.containsKey(m_preserveIncompleteEnvsModel.getConfigName())) {
            m_preserveIncompleteEnvsModel.loadSettingsFrom(settings);
        }
        m_sourceOsModel.loadSettingsFrom(settings);
    }

    @Override
    protected void saveInternals(final File nodeInternDir, final ExecutionMonitor exec)
        throws IOException, CanceledExecutionException {
        // Nothing to do.
    }

    @Override
    protected void loadInternals(final File nodeInternDir, final ExecutionMonitor exec)
        throws IOException, CanceledExecutionException {
        // Nothing to do.
    }

    @Override
    protected PortObjectSpec[] configure(final PortObjectSpec[] inSpecs) throws InvalidSettingsException {
        final List<CondaPackageSpec> packages = m_packagesConfig.getIncludedPackages();
        if (m_environmentNameModel.getStringValue()
            .equals(CondaEnvironmentIdentifier.PLACEHOLDER_CONDA_ENV.getName())) {
            throw new InvalidSettingsException("No conda environment selected.");
        }
        if (packages.isEmpty()) {
            throw new InvalidSettingsException("Cannot propagate an empty environment. Select at least one package.");
        }
        final List<CondaPackageSpec> installedByPip = new ArrayList<>();
        final boolean pipExists = Conda.filterPipInstalledPackages(packages, installedByPip, null);
        if (!pipExists && !installedByPip.isEmpty()) {
            throw new InvalidSettingsException("There are packages included in the environment that need to be "
                + "installed using pip. Therefore you also need to include package 'pip'.");
        }

        // Always output environment flow variable, but use placeholder path while execute hasn't been run
        final String environmentName = m_environmentNameModel.getStringValue();
        if (environmentName == null || environmentName.isBlank()) {
            throw new InvalidSettingsException("The environment name may not be blank.");
        }
        pushEnvironmentFlowVariable(environmentName, CondaEnvironmentIdentifier.NOT_EXECUTED_PATH_PLACEHOLDER);
        return new PortObjectSpec[]{FlowVariablePortObjectSpec.INSTANCE};
    }

    @Override
    protected PortObject[] execute(final PortObject[] inObjects, final ExecutionContext exec) throws Exception {
        final Conda conda = createConda();
        final String environmentName = m_environmentNameModel.getStringValue();
        final String targetOs = getCurrentOsType();
        final boolean sameOs = targetOs.equals(m_sourceOsModel.getStringValue());

        final List<CondaPackageSpec> packages =
            filterIncludedPackagesForTargetOs(m_packagesConfig.getIncludedPackages(), targetOs);

        final var p = checkWhetherToCreateEnvironment(conda, environmentName, packages,
            m_validationMethodModel.getStringValue(), sameOs);
        final boolean createEnvironment = p.getFirst().getFirst();
        final Optional<CondaEnvironmentIdentifier> existingEnvironment = p.getFirst().getSecond();
        final String creationMessage = p.getSecond();

        if (createEnvironment) {
            exec.setMessage(creationMessage);
            LOGGER.info(creationMessage);

            // Create a temporary backup of the existing environment
            try (final EnvironmentBackup envBackup = new EnvironmentBackup(existingEnvironment.orElse(null))) {
                try {
                    conda.createEnvironment(environmentName, packages, sameOs,
                        new Monitor(packages.size(), exec.getProgressMonitor(), exec::setProgress, errorMsg -> {
                            throw new KNIMEException(errorMsg).toUnchecked();
                        }));
                } catch (final IOException ex) {
                    handleFailedEnvCreation(conda, environmentName, envBackup);
                    throw ex;
                } catch (final CondaCanceledExecutionException ex) {
                    handleCanceledEnvCreation(conda, environmentName, envBackup);
                    throw ex;
                }
            }
        }

        final List<CondaEnvironmentIdentifier> environments = conda.getEnvironments();
        final Optional<CondaEnvironmentIdentifier> environment = findEnvironment(environmentName, environments);
        if (!environment.isPresent()) {
            if (createEnvironment) {
                throw new IllegalStateException("Failed to create Conda environment '" + environmentName
                    + "' for unknown reasons.\nPlease check the log for any relevant information.");
            } else {
                throw new IllegalStateException("Conda environment '" + environmentName + "' does not exist anymore.\n"
                    + "Please ensure that KNIME has exclusive control over Conda environment creation and deletion.");
            }
        }

        pushEnvironmentFlowVariable(environmentName, environment.get().getDirectoryPath());

        return new PortObject[]{FlowVariablePortObject.INSTANCE};
    }

    private static List<CondaPackageSpec> filterIncludedPackagesForTargetOs(
        final List<CondaPackageSpec> allIncludedPackages, final String targetOs) throws IOException {
        populatePlatformPackageFilter(targetOs);
        return allIncludedPackages.stream() //
            .filter(pkg -> !platformPackageFilter.excludesPackage(pkg.getName())) //
            .collect(Collectors.toList());
    }

    private static synchronized void populatePlatformPackageFilter(final String targetOs) throws IOException {
        if (platformPackageFilter == null) {
            if (targetOs.equals(OS_LINUX)) {
                platformPackageFilter = PlatformCondaPackageFilter.createLinuxFilterList();
            } else if (targetOs.equals(OS_MAC)) {
                platformPackageFilter = PlatformCondaPackageFilter.createMacFilterList();
            } else if (targetOs.equals(OS_WINDOWS)) {
                platformPackageFilter = PlatformCondaPackageFilter.createWindowsFilterList();
            } else {
                throw new IllegalStateException(
                    "Unknown operating system identifier: " + targetOs + ". This is an implementation error.");
            }
        }
    }

    /**
     * Checks whether the requested conda environment should be created. If the validationMethod is set to "name" the
     * env is only created if the name does not exist. If the validationMethod is set to "name_packages" the env is
     * created if the name does not exist or the existing env does not contain all requested packages. If the
     * validationMethod is set to "overwrite" the env is always created.
     *
     * @return a nested pair with a boolean stating if the env should be created, an optional identifier of the existing
     *         environment and a creation message
     */
    private static Pair<Pair<Boolean, Optional<CondaEnvironmentIdentifier>>, String> checkWhetherToCreateEnvironment(
        final Conda conda, final String environmentName, final List<CondaPackageSpec> requiredPackages,
        final String validationMethod, final boolean sameOs) throws IOException, InvalidSettingsException {
        final Optional<CondaEnvironmentIdentifier> existingEnvironment =
            findEnvironment(environmentName, conda.getEnvironments());
        final boolean nameExists = existingEnvironment.isPresent();

        final boolean createEnvironment;
        String creationMessage;
        if (VALIDATION_METHOD_NAME.equals(validationMethod)
            || VALIDATION_METHOD_NAME_PACKAGES.equals(validationMethod)) {
            if (nameExists && VALIDATION_METHOD_NAME_PACKAGES.equals(validationMethod)) {
                createEnvironment = !requiredPackagesExist(conda, environmentName, requiredPackages, sameOs);
                creationMessage = "Environment '" + environmentName
                    + "' exists but does not contain all of the configured packages. Overwriting the environment.";
            } else {
                createEnvironment = !nameExists;
                creationMessage = "Environment '" + environmentName + "' does not exist. Creating the environment.";
            }
        } else if (VALIDATION_METHOD_OVERWRITE.equals(validationMethod)) {
            createEnvironment = true;
            creationMessage = nameExists //
                ? ("Environment '" + environmentName + "' exists. Overwriting the environment.") //
                : ("Environment '" + environmentName + "' does not exist. Creating the environment.");
        } else {
            throw new InvalidSettingsException("Invalid validation method selected. Allowed methods: "
                + Arrays.toString(createEnvironmentValidationMethodKeys()));
        }
        return new Pair<>(new Pair<>(createEnvironment, existingEnvironment),
            creationMessage + " This might take a while...");
    }

    private static boolean requiredPackagesExist(final Conda conda, final String environmentName,
        final List<CondaPackageSpec> requiredPackages, final boolean sameOs) throws IOException {
        final List<CondaPackageSpec> existingPackages = conda.getPackages(environmentName);
        // Ignore/zero build specs if source and target operating systems differ.
        final UnaryOperator<CondaPackageSpec> adaptToOs =
            pkg -> sameOs ? pkg : new CondaPackageSpec(pkg.getName(), pkg.getVersion(), "", pkg.getChannel());
        final Set<CondaPackageSpec> existingPackagesBuildSpecAdapted = existingPackages.stream() //
            .map(adaptToOs) //
            .collect(Collectors.toSet());
        return requiredPackages.stream() //
            .map(adaptToOs) //
            .allMatch(existingPackagesBuildSpecAdapted::contains);
    }

    private void handleFailedEnvCreation(final Conda conda, final String environmentName,
        final EnvironmentBackup envBackup) {
        if (m_preserveIncompleteEnvsModel.getBooleanValue()) {
            // Creating the environment failed -> We still keep it
            LOGGER.warn("Creating the environment failed. There might be an incomplete environment present. "
                + "Proceed with caution!");
        } else {
            // Creating the environment failed -> We make sure to remove what might be already there
            LOGGER.warn("Creating the environment failed. "
                + "If an incomplete environment has been created, it will be removed and the original environment, if "
                + "any, will be recovered.");
            deleteEnvironmentAndRecoverBackup(conda, environmentName, envBackup);
        }
    }

    private static void handleCanceledEnvCreation(final Conda conda, final String environmentName,
        final EnvironmentBackup envBackup) {
        // Creating the environment canceled -> We make sure to remove what might be already there
        LOGGER.info("Environment creation canceled. If an incomplete environment has been created, it will be removed "
            + "and the original environment, if any, will be recovered.");
        deleteEnvironmentAndRecoverBackup(conda, environmentName, envBackup);
    }

    private static void deleteEnvironmentAndRecoverBackup(final Conda conda, final String environmentName,
        final EnvironmentBackup envBackup) {
        // Clear the interrupted state and remember
        final boolean interrupted = Thread.interrupted();

        // Sonar: This method will only be called if the node fails or is canceled. We do not want any exceptions thrown
        // here to override the original cause of why the environment creation was terminated. So only log them and let
        // the node report the original cause to the runtime.

        // Delete the environment
        try {
            conda.deleteEnvironment(environmentName);
        } catch (final Exception ex) { // NOSONAR See above.
            LOGGER.warn("Deleting the environment directory failed.", ex);
        }

        // Try to recover the backup
        try {
            envBackup.recover();
        } catch (final Exception ex) { // NOSONAR See above.
            LOGGER.warn("Could not recover the original environment.", ex);
        }

        // Re-interrupt the thread if it was interrupted
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    protected void reset() {
        // Nothing to do.
    }

    private static String getCurrentOsType() {
        final String osType;
        if (SystemUtils.IS_OS_LINUX) {
            osType = OS_LINUX;
        } else if (SystemUtils.IS_OS_MAC) {
            osType = OS_MAC;
        } else if (SystemUtils.IS_OS_WINDOWS) {
            osType = OS_WINDOWS;
        } else {
            throw Conda.createUnknownOSException();
        }
        return osType;
    }

    static Conda createConda() throws InvalidSettingsException {
        final var condaInstallationPath = CondaPreferences.getCondaInstallationDirectory();
        try {
            return new Conda(condaInstallationPath);
        } catch (final IOException ex) {
            throw new InvalidSettingsException("Failed to reach out to the Conda installation located at '"
                + condaInstallationPath + "'.\nPlease make sure Conda is properly configured in the Preferences of "
                + "the KNIME Conda Integration.\nThen select the Conda environment to propagate via the "
                + "configuration dialog of this node.", ex);
        }
    }

    /**
     * @return The descriptions of all installed Conda environments except for the {@link Conda.ROOT_ENVIRONMENT_NAME
     *         base} environment. The base environment cannot be overwritten. It can therefore not be propagated when
     *         using the {@link #VALIDATION_METHOD_NAME_PACKAGES} or {@link #VALIDATION_METHOD_OVERWRITE} validation
     *         options.
     *
     *         Throughout the Conda Environment Propagation node we use the name of an environment to identify it.
     *         Environments outside of the conda root prefix cannot be addressed by name, and thus we drop them from the
     *         list.
     */
    static List<CondaEnvironmentIdentifier> getSelectableEnvironments(final Conda conda) throws IOException {
        var envDirsConfig = conda.getEnvsDirs();
        Predicate<CondaEnvironmentIdentifier> isInConfiguredEnvDir =
            env -> envDirsConfig.stream().anyMatch(dir -> env.getDirectoryPath().contains(dir));

        return conda.getEnvironments().stream() //
            .filter(e -> !Objects.equals(e.getName(), Conda.ROOT_ENVIRONMENT_NAME)) //
            .filter(isInConfiguredEnvDir) //
            .collect(Collectors.toList());
    }

    private static Optional<CondaEnvironmentIdentifier> findEnvironment(final String environmentName,
        final List<CondaEnvironmentIdentifier> environments) {
        return environments.stream() //
            .filter(e -> e.getName().equals(environmentName)) //
            .findFirst();
    }

    private void pushEnvironmentFlowVariable(final String environmentName, final String environmentDirectoryPath) {
        pushFlowVariable(m_outputVariableNameModel.getStringValue(), CondaEnvironmentType.INSTANCE,
            new CondaEnvironmentSpec(new CondaEnvironmentIdentifier(environmentName, environmentDirectoryPath)));
    }

    private static final class Monitor extends CondaEnvironmentCreationMonitor {

        private final double m_progressPerPackage;

        private final NodeProgressMonitor m_monitor;

        private final NodeContext m_nodeContext;

        private final Consumer<String> m_msgConsumer;

        public Monitor(final int numPackages, final NodeProgressMonitor monitor, final Consumer<String> msgConsumer,
            final Consumer<String> nodeErrorMessageConsumer) {
            super(nodeErrorMessageConsumer);
            m_progressPerPackage = 1d / numPackages;
            m_monitor = monitor;
            m_nodeContext = NodeContext.getContext();
            m_msgConsumer = msgConsumer;
        }

        @Override
        public void setProgressMessage(final String msg) {
            m_msgConsumer.accept(msg);
        }

        @Override
        protected void handleWarningMessage(final String warning) {
            NodeContext.pushContext(m_nodeContext);
            try {
                LOGGER.warn(warning);
            } finally {
                NodeContext.removeLastContext();
            }
        }

        @Override
        protected void handlePackageDownloadProgress(final String currentPackage, final boolean packageFinished,
            final double progress) {
            if (!packageFinished) {
                m_monitor.setMessage("Downloading package '" + currentPackage + "'...");
            } else {
                final Double totalProgress = m_monitor.getProgress();
                m_monitor.setProgress((totalProgress != null ? totalProgress : 0d) + m_progressPerPackage,
                    "Creating Conda environment...");
            }
        }
    }

    private static final class EnvironmentBackup implements AutoCloseable {

        private final Path m_existingEnv;

        private final Path m_backupEnv;

        private EnvironmentBackup(final CondaEnvironmentIdentifier environment) {
            if (environment == null) {
                m_existingEnv = null;
                m_backupEnv = null;
            } else {
                final var paths = createBackup(environment);
                if (paths.isEmpty()) {
                    m_existingEnv = null;
                    m_backupEnv = null;
                } else {
                    m_existingEnv = paths.get().getFirst();
                    m_backupEnv = paths.get().getSecond();
                }
            }
        }

        private static Optional<Pair<Path, Path>> createBackup(final CondaEnvironmentIdentifier environment) {
            Path backupFolder = null;
            try {
                final Path existingEnv = Path.of(environment.getDirectoryPath());
                backupFolder = existingEnv //
                    .normalize().getParent() // envs directory /foo/anaconda3/envs
                    // temporary folder for the backup /foo/anaconda3/envs/tmp-6ffc2c08
                    .resolve("tmp-" + UUID.randomUUID().toString());
                final Path backupEnv = backupFolder.resolve(existingEnv.getFileName());
                // NOTE: this should always work because it's on the same file system
                Files.createDirectories(backupFolder);
                Files.move(existingEnv, backupEnv);
                return Optional.of(Pair.create(existingEnv, backupEnv));
            }
            // Sonar: Catch all exceptions to make sure this does not hinder the node execution
            catch (final Exception ex) { // NOSONAR
                LOGGER.warn(
                    "Could not back up environment. The environment will be overwritten without chance for recovery.",
                    ex);
                // Clean up the backup folder if the backup did not succeed
                deleteBackupIfExists(backupFolder);
            }

            return Optional.empty();
        }

        private void recover() throws IOException {
            if (m_backupEnv != null && m_existingEnv != null) {
                Files.move(m_backupEnv, m_existingEnv);
            }
        }

        @Override
        public void close() {
            // Delete the backup folder if it exists (no matter if the backup was successful or not)
            if (m_backupEnv != null) {
                deleteBackupIfExists(m_backupEnv.getParent());
            }
        }

        private static void deleteBackupIfExists(final Path folder) {
            if (folder != null) {
                try {
                    if (Files.exists(folder)) {
                        PathUtils.deleteDirectoryIfExists(folder);
                    }
                }
                // Sonar: Catch all exceptions to make sure this does not hinder the node execution
                catch (final Exception ex) { // NOSONAR
                    LOGGER.warn("Could not clean up backup directory. The directory "
                        + folder.toAbsolutePath().toString() + " exists and needs to be deleted manually.", ex);
                }
            }
        }
    }
}
