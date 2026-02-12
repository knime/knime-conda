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
 *   Jan 15, 2026 (Marc Lehner): created
 */
package org.knime.pixi.nodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.knime.conda.envinstall.pixi.PixiBinary;
import org.knime.conda.envinstall.pixi.PixiBinary.CallResult;
import org.knime.conda.envinstall.pixi.PixiBinary.PixiBinaryLocationException;
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.NodeLogger;
import org.knime.core.node.NodeSettingsRO;
import org.knime.core.node.NodeSettingsWO;
import org.knime.core.webui.node.dialog.defaultdialog.internal.button.ButtonWidget;
import org.knime.core.webui.node.dialog.defaultdialog.internal.button.CancelableActionHandler;
import org.knime.core.webui.node.dialog.defaultdialog.widget.handler.WidgetHandlerException;
import org.knime.node.parameters.NodeParameters;
import org.knime.node.parameters.NodeParametersInput;
import org.knime.node.parameters.Widget;
import org.knime.node.parameters.array.ArrayWidget;
import org.knime.node.parameters.layout.After;
import org.knime.node.parameters.layout.Layout;
import org.knime.node.parameters.persistence.NodeParametersPersistor;
import org.knime.node.parameters.persistence.Persistor;
import org.knime.node.parameters.updates.Effect;
import org.knime.node.parameters.updates.Effect.EffectType;
import org.knime.node.parameters.updates.EffectPredicate;
import org.knime.node.parameters.updates.EffectPredicateProvider;
import org.knime.node.parameters.updates.ParameterReference;
import org.knime.node.parameters.updates.StateProvider;
import org.knime.node.parameters.updates.ValueProvider;
import org.knime.node.parameters.updates.ValueReference;
import org.knime.node.parameters.widget.choices.Label;
import org.knime.node.parameters.widget.choices.ValueSwitchWidget;
import org.knime.node.parameters.widget.message.TextMessage;
import org.knime.node.parameters.widget.message.TextMessage.MessageType;
import org.knime.node.parameters.widget.text.TextAreaWidget;
import org.knime.pixi.port.PixiUtils;

/**
 * Node Parameters for the Python Environment Provider node. Combines input methods from array-based, TOML-based, and
 * file reader approaches.
 *
 * @author Marc Lehner, KNIME GmbH, Konstanz, Germany
 * @since 5.11
 */
@SuppressWarnings("restriction")
public class PythonEnvironmentProviderNodeParameters implements NodeParameters {

    private static final NodeLogger LOGGER = NodeLogger.getLogger(PythonEnvironmentProviderNodeParameters.class);

    // Layout sections
    static final class mainInputSelectionSection {
    }

    @After(mainInputSelectionSection.class)
    static final class simpleInputSection {
    }

    @After(mainInputSelectionSection.class)
    static final class tomlEditorSection {
    }

    @After(mainInputSelectionSection.class)
    static final class yamlEditorSection {
    }

    @After(simpleInputSection.class)
    @After(tomlEditorSection.class)
    @After(yamlEditorSection.class)
    static final class lockFileSection {
    }

    // Input source selection
    enum MainInputSource {
            @Label("Packages")
            SIMPLE,

            @Label("TOML editor")
            TOML_EDITOR,

            @Label("YAML editor")
            YAML_EDITOR
    }

    @Widget(title = "Input source", description = "Choose how to define the Python environment")
    @Layout(mainInputSelectionSection.class)
    @ValueReference(MainInputSourceRef.class)
    @ValueSwitchWidget
    MainInputSource m_mainInputSource = MainInputSource.SIMPLE;

    interface MainInputSourceRef extends ParameterReference<MainInputSource> {
    }

    // Simple/Packages input
    @Widget(title = "Packages", description = "Specify the packages to include in the environment")
    @Layout(simpleInputSection.class)
    @ArrayWidget(elementLayout = ArrayWidget.ElementLayout.HORIZONTAL_SINGLE_LINE, addButtonText = "Add package")
    @ValueReference(PackageArrayRef.class)
    @Effect(predicate = InputIsSimple.class, type = EffectType.SHOW)
    PackageSpec[] m_packages = new PackageSpec[]{new PackageSpec("python", PackageSource.CONDA, "3.14", "3.14"),
        new PackageSpec("knime-python-base", PackageSource.CONDA, "5.9", "5.9")};

    interface PackageArrayRef extends ParameterReference<PackageSpec[]> {
    }

    static final class InputIsSimple implements EffectPredicateProvider {
        @Override
        public EffectPredicate init(final PredicateInitializer i) {
            return i.getEnum(MainInputSourceRef.class).isOneOf(MainInputSource.SIMPLE);
        }
    }

    // TOML editor input
    @Widget(title = "Environment specification (pixi.toml)", description = """
            Content of the pixi.toml manifest file that describes the environment.
            """)
    @Layout(tomlEditorSection.class)
    @TextAreaWidget(rows = 20)
    @ValueReference(TomlContentRef.class)
    @Effect(predicate = InputIsToml.class, type = EffectType.SHOW)
    String m_pixiTomlContent = """
            [workspace]
            channels = ["knime", "conda-forge"]
            platforms = ["win-64", "linux-64", "osx-64", "osx-arm64"]

            [dependencies]
            python = "3.14.*"
            knime-python-base = "5.9.*"
            """;

    interface TomlContentRef extends ParameterReference<String> {
    }

    static final class InputIsToml implements EffectPredicateProvider {
        @Override
        public EffectPredicate init(final PredicateInitializer i) {
            return i.getEnum(MainInputSourceRef.class).isOneOf(MainInputSource.TOML_EDITOR);
        }
    }

    // YAML editor input
    @Widget(title = "Environment specification (conda environment.yaml)",
        description = """
                Content of the conda environment.yaml file that describes the environment.
                This will be imported into pixi using `pixi init --import` and converted to a pixi.toml manifest.
                The environment will automatically be configured to work on all major platforms (win-64, linux-64, osx-64, osx-arm64).
                """)
    @Layout(yamlEditorSection.class)
    @TextAreaWidget(rows = 20)
    @ValueReference(YamlContentRef.class)
    @Effect(predicate = InputIsYaml.class, type = EffectType.SHOW)
    String m_envYamlContent = """
            name: myenv
            channels:
              - knime
              - conda-forge
            dependencies:
              - python=3.14.*
              - knime-python-base=5.9.*
            """;

    interface YamlContentRef extends ParameterReference<String> {
    }

    static final class InputIsYaml implements EffectPredicateProvider {
        @Override
        public EffectPredicate init(final PredicateInitializer i) {
            return i.getEnum(MainInputSourceRef.class).isOneOf(MainInputSource.YAML_EDITOR);
        }
    }

    // TODO
    // We should have only one button:
    // - Show "Resolve Dependencies" if no lock file exists yet for the current inputs
    // - Show "Update Dependencies" if a lock file already exists, to update resolved package versions
    // - Show "Cancel" while action is running

    // Lock file generation
    @Widget(title = "Check compatibility",
        description = "Click to check whether this environment can be constructed on all selected operating systems")
    @Layout(lockFileSection.class)
    @ButtonWidget(actionHandler = PixiLockActionHandler.class, updateHandler = PixiLockUpdateHandler.class)
    @ValueReference(ButtonFieldRef.class)
    String m_pixiLockButton;

    @Widget(title = "Update dependencies",
        description = "Click to update all dependencies to their latest compatible versions and update the lock file")
    @Layout(lockFileSection.class)
    @ButtonWidget(actionHandler = PixiUpdateActionHandler.class, updateHandler = PixiUpdateUpdateHandler.class)
    @ValueReference(ButtonFieldRef.class)
    String m_pixiUpdateButton;

    interface ButtonFieldRef extends ParameterReference<String> {
    }

    // Hidden field that stores lock file content from button, reset to empty when input changes
    @ValueReference(PixiLockFileRef.class)
    @ValueProvider(ResetLockFileProvider.class)
    String m_pixiLockFileContent = "";

    interface PixiLockFileRef extends ParameterReference<String> {
    }

    // Hidden field that tracks if platform validation shows warning/error
    @ValueReference(PlatformValidationIsWarningRef.class)
    @ValueProvider(PlatformValidationIsWarningProvider.class)
    Boolean m_platformValidationIsWarning = false;

    interface PlatformValidationIsWarningRef extends ParameterReference<Boolean> {
    }

    @TextMessage(PlatformValidationProvider.class)
    @Layout(tomlEditorSection.class)
    @Effect(predicate = PlatformValidationIsWarning.class, type = EffectType.SHOW)
    Void m_platformValidationMessage;

    @TextMessage(ValidationMessageProvider.class)
    @Layout(lockFileSection.class)
    Void m_validationMessage;

    // Read-only display of lock file content (advanced setting)
    @Widget(title = "Lock file content", description = "Content of the generated or loaded pixi.lock file.",
        advanced = true)
    @Layout(lockFileSection.class)
    @TextAreaWidget(rows = 10)
    @Persistor(DoNotPersist.class)
    @ValueProvider(LockContentDisplayProvider.class)
    String m_lockFileDisplay = "";

    static final class LockContentDisplayProvider implements StateProvider<String> {
        private Supplier<String> m_lockContentSupplier;

        @Override
        public void init(final StateProviderInitializer initializer) {
            initializer.computeOnValueChange(PixiLockFileRef.class);
            m_lockContentSupplier = initializer.getValueSupplier(PixiLockFileRef.class);
        }

        @Override
        public String computeState(final NodeParametersInput context) {
            final String lockContent = m_lockContentSupplier.get();
            return (lockContent != null) ? lockContent : "";
        }
    }

    // Helper methods
    String getPixiLockFileContent() {
        return m_pixiLockFileContent;
    }

    String getPixiTomlFileContent() throws IOException {
        return PixiManifestResolver.getTomlContent(m_mainInputSource, m_packages, m_pixiTomlContent, m_envYamlContent,
            LOGGER);
    }

    // Package specification
    enum PackageSource {
            @Label("Conda")
            CONDA, @Label("Pip")
            PIP
    }

    static final class PackageSpec implements NodeParameters {
        @Widget(title = "Package name", description = "The name of the package")
        String m_packageName = "";

        @Widget(title = "Source", description = "Package source (Conda or Pip)")
        PackageSource m_source = PackageSource.CONDA;

        @Widget(title = "Min version", description = "Minimum version (inclusive, optional)")
        String m_minVersion = "";

        @Widget(title = "Max version", description = "Maximum version (exclusive, optional)")
        String m_maxVersion = "";

        PackageSpec() {
        }

        PackageSpec(final String name, final PackageSource source, final String minVersion, final String maxVersion) {
            m_packageName = name;
            m_source = source;
            m_minVersion = minVersion;
            m_maxVersion = maxVersion;
        }
    }

    // Button action handler for lock file generation
    static final class PixiLockActionHandler extends CancelableActionHandler<String, TomlContentGetter> {

        private static final NodeLogger LOGGER = NodeLogger.getLogger(PixiLockActionHandler.class);

        @Override
        protected String invoke(final TomlContentGetter deps, final NodeParametersInput context)
            throws WidgetHandlerException {
            final String tomlContent;
            try {
                tomlContent = deps.getTomlContent();
            } catch (IOException e) {
                throw new WidgetHandlerException("Failed to get TOML content: " + e.getMessage());
            }
            LOGGER.debug("Button clicked - running pixi lock...");
            if (tomlContent == null || tomlContent.isBlank()) {
                throw new WidgetHandlerException("No manifest content provided");
            }

            try {
                // TODO we currently use the same directory as for the final installations of the port objects
                // However, we should use a temporary directory just for the lock file generation that is deleted afterwards,
                // otherwise we collect a lot of lock files of potentially intermediate states (in the worst case not even in a temporary directory).
                final Path projectDir = PixiUtils.resolveProjectDirectory(tomlContent);
                final Path pixiHome = projectDir.resolve(".pixi-home"); // TODO what's about the PIXI_HOME???
                Files.createDirectories(pixiHome);

                final Map<String, String> extraEnv = Map.of("PIXI_HOME", pixiHome.toString());
                final String[] pixiArgs = {"--color", "never", "--no-progress", "lock"};

                LOGGER.debug("Running pixi lock in: " + projectDir);
                final CallResult callResult = PixiBinary.callPixi(projectDir, extraEnv, pixiArgs);

                if (callResult.returnCode() != 0) {
                    String errorDetails = PixiUtils.getMessageFromCallResult(callResult);
                    LOGGER.warn("Pixi lock failed: " + errorDetails);
                    throw new WidgetHandlerException("Pixi lock failed:\n" + errorDetails);
                }

                // Read the generated lock file
                final Path lockFilePath = projectDir.resolve("pixi.lock");
                if (Files.exists(lockFilePath)) {
                    final String lockContent = Files.readString(lockFilePath);
                    LOGGER.debug("Lock file generated (" + lockContent.length() + " bytes)");
                    return lockContent;
                } else {
                    throw new WidgetHandlerException("Lock file was not generated at: " + lockFilePath);
                }
            } catch (IOException | PixiBinaryLocationException e) {
                throw new WidgetHandlerException("Failed to generate lock file: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WidgetHandlerException("Lock generation was interrupted: " + e.getMessage());
            }
        }

        @Override
        protected String getButtonText(final States state) {
            return switch (state) {
                case READY -> "Lock Environment";
                case CANCEL -> "Cancel";
                case DONE -> "Lock Environment";
            };
        }

        @Override
        protected boolean isMultiUse() {
            return true; // Allow re-locking
        }
    }

    static final class TomlContentGetter {
        private static final NodeLogger LOGGER = NodeLogger.getLogger(TomlContentGetter.class);

        @ValueReference(MainInputSourceRef.class)
        MainInputSource m_mainInputSource;

        @ValueReference(PackageArrayRef.class)
        PackageSpec[] m_packages;

        @ValueReference(TomlContentRef.class)
        String m_pixiTomlContent;

        @ValueReference(YamlContentRef.class)
        String m_envYamlContent;


        String getTomlContent() throws IOException {
            return PixiManifestResolver.getTomlContent(m_mainInputSource, m_packages, m_pixiTomlContent,
                m_envYamlContent, LOGGER);
        }
    }

    static final class PixiLockUpdateHandler extends CancelableActionHandler.UpdateHandler<String, TomlContentGetter> {
    }

    static final class PixiUpdateActionHandler
        extends PixiParameterUtils.AbstractPixiUpdateActionHandler<TomlContentGetter> {

        public PixiUpdateActionHandler() {
            super("[PythonEnvironmentProviderNode]");
        }

        @Override
        protected String getManifestContent(final TomlContentGetter contentGetter) {
            try {
                return contentGetter.getTomlContent();
            } catch (IOException e) {
                throw new RuntimeException("Failed to get TOML content: " + e.getMessage(), e);
            }
        }

        @Override
        protected String prepareManifestContent(final String content) throws Exception {
            // Content is already TOML, no conversion needed
            return content;
        }
    }

    static final class PixiUpdateUpdateHandler
        extends CancelableActionHandler.UpdateHandler<String, TomlContentGetter> {
    }

    /**
     * Resets lock file to empty when any input content changes.
     */
    static final class ResetLockFileProvider implements StateProvider<String> {

        private static final NodeLogger LOGGER = NodeLogger.getLogger(ResetLockFileProvider.class);

        private Supplier<MainInputSource> m_inputSourceSupplier;

        private Supplier<PackageSpec[]> m_packagesSupplier;

        private Supplier<String> m_tomlContentSupplier;

        private Supplier<String> m_yamlContentSupplier;

        private Supplier<String> m_buttonFieldSupplier;

        private MainInputSource m_lastInputSource;

        private PackageSpec[] m_lastPackages;

        private String m_lastTomlContent;

        private String m_lastYamlContent;

        @Override
        public void init(final StateProviderInitializer initializer) {
            LOGGER.debug("Initializing...");
            initializer.computeOnValueChange(MainInputSourceRef.class);
            initializer.computeOnValueChange(PackageArrayRef.class);
            initializer.computeOnValueChange(TomlContentRef.class);
            initializer.computeOnValueChange(YamlContentRef.class);
            initializer.computeOnValueChange(ButtonFieldRef.class);

            m_inputSourceSupplier = initializer.getValueSupplier(MainInputSourceRef.class);
            m_packagesSupplier = initializer.getValueSupplier(PackageArrayRef.class);
            m_tomlContentSupplier = initializer.getValueSupplier(TomlContentRef.class);
            m_yamlContentSupplier = initializer.getValueSupplier(YamlContentRef.class);
            m_buttonFieldSupplier = initializer.getValueSupplier(ButtonFieldRef.class);
        }

        @Override
        public String computeState(final NodeParametersInput context) {
            final MainInputSource currentInputSource = m_inputSourceSupplier.get();
            final PackageSpec[] currentPackages = m_packagesSupplier.get();
            final String currentTomlContent = m_tomlContentSupplier.get();
            final String currentYamlContent = m_yamlContentSupplier.get();
            final String buttonValue = m_buttonFieldSupplier.get();

            LOGGER.debug("Computing state for: " + currentInputSource);
            LOGGER.debug("Button value: " + (buttonValue != null ? buttonValue.length() + " chars" : "null"));

            // Check if any relevant content changed
            boolean contentChanged = false;

            if (m_lastInputSource != null && currentInputSource != m_lastInputSource) {
                LOGGER.debug("Input source changed: " + m_lastInputSource + " -> " + currentInputSource);
                contentChanged = true;
            }

            if (currentInputSource == MainInputSource.SIMPLE && m_lastPackages != null
                && !java.util.Arrays.equals(m_lastPackages, currentPackages)) {
                LOGGER.debug("Packages changed");
                contentChanged = true;
            }

            if (currentInputSource == MainInputSource.TOML_EDITOR && m_lastTomlContent != null
                && !m_lastTomlContent.equals(currentTomlContent)) {
                LOGGER.debug("TOML content changed");
                contentChanged = true;
            }

            if (currentInputSource == MainInputSource.YAML_EDITOR && m_lastYamlContent != null
                && !m_lastYamlContent.equals(currentYamlContent)) {
                LOGGER.debug("YAML content changed");
                contentChanged = true;
            }

            // Update last values
            m_lastInputSource = currentInputSource;
            m_lastPackages = currentPackages;
            m_lastTomlContent = currentTomlContent;
            m_lastYamlContent = currentYamlContent;

            // If content changed, reset lock file; otherwise return button value
            if (contentChanged) {
                LOGGER.debug("Content changed - resetting lock file");
                return "";
            }
            LOGGER.debug("Content unchanged - keeping lock file");
            return buttonValue != null ? buttonValue : "";
        }
    }

    // Validation message provider
    static final class ValidationMessageProvider implements StateProvider<Optional<TextMessage.Message>> {

        private static final NodeLogger LOGGER = NodeLogger.getLogger(ValidationMessageProvider.class);

        private Supplier<String> m_lockContentSupplier;

        @Override
        public void init(final StateProviderInitializer initializer) {
            initializer.computeOnValueChange(PixiLockFileRef.class);
            m_lockContentSupplier = initializer.getValueSupplier(PixiLockFileRef.class);
        }

        @Override
        public Optional<TextMessage.Message> computeState(final NodeParametersInput context) {
            final String lockContent = m_lockContentSupplier.get();
            LOGGER.debug("Lock file content: " + (lockContent == null ? "null" : lockContent.length() + " chars"));

            if (lockContent == null || lockContent.isBlank()) {
                LOGGER.debug("No lock file - showing info message");
                return Optional.of(new TextMessage.Message("Lock file status",
                    "No lock file generated yet. Click 'Check compatibility' to validate the environment.",
                    MessageType.INFO));
            }

            LOGGER.debug("Lock file present - showing success message");
            // If lock file exists and is not empty, it's valid
            return Optional.of(new TextMessage.Message("Environment validated",
                "Environment validated successfully. Lock file generated.", MessageType.SUCCESS));
        }
    }

    /**
     * Predicate that returns true when platform validation message is a warning or error.
     */
    static final class PlatformValidationIsWarning implements EffectPredicateProvider {

        @Override
        public EffectPredicate init(final PredicateInitializer i) {
            return i.getBoolean(PlatformValidationIsWarningRef.class).isTrue();
        }
    }

    /**
     * State provider that computes whether platform validation message is a warning or error.
     */
    static final class PlatformValidationIsWarningProvider implements StateProvider<Boolean> {

        private Supplier<String> m_tomlContentSupplier;

        private Supplier<MainInputSource> m_inputSourceSupplier;

        @Override
        public void init(final StateProviderInitializer initializer) {
            initializer.computeOnValueChange(TomlContentRef.class);
            initializer.computeOnValueChange(MainInputSourceRef.class);
            initializer.computeAfterOpenDialog();
            m_tomlContentSupplier = initializer.getValueSupplier(TomlContentRef.class);
            m_inputSourceSupplier = initializer.getValueSupplier(MainInputSourceRef.class);
        }

        @Override
        public Boolean computeState(final NodeParametersInput context) {
            final MainInputSource inputSource = m_inputSourceSupplier.get();

            // Only validate for TOML_EDITOR mode
            if (inputSource != MainInputSource.TOML_EDITOR) {
                return false;
            }

            final String tomlContent = m_tomlContentSupplier.get();
            return PixiTomlValidator.validatePlatforms(tomlContent).isWarningOrError();
        }
    }

    /**
     * State provider for platform validation message.
     */
    static final class PlatformValidationProvider implements StateProvider<Optional<TextMessage.Message>> {

        private Supplier<String> m_tomlContentSupplier;

        private Supplier<MainInputSource> m_inputSourceSupplier;

        @Override
        public void init(final StateProviderInitializer initializer) {
            initializer.computeOnValueChange(TomlContentRef.class);
            initializer.computeOnValueChange(MainInputSourceRef.class);
            initializer.computeAfterOpenDialog();
            m_tomlContentSupplier = initializer.getValueSupplier(TomlContentRef.class);
            m_inputSourceSupplier = initializer.getValueSupplier(MainInputSourceRef.class);
        }

        @Override
        public Optional<TextMessage.Message> computeState(final NodeParametersInput context) {
            final MainInputSource inputSource = m_inputSourceSupplier.get();

            // Only validate for TOML_EDITOR mode
            if (inputSource != MainInputSource.TOML_EDITOR) {
                return Optional.empty();
            }

            final String tomlContent = m_tomlContentSupplier.get();
            return PixiTomlValidator.toMessage(PixiTomlValidator.validatePlatforms(tomlContent));
        }
    }

    /**
     * Custom persistor that doesn't persist the field - used for computed/display-only fields.
     */
    static final class DoNotPersist implements NodeParametersPersistor<String> {
        @Override
        public String load(final NodeSettingsRO settings) throws InvalidSettingsException {
            return null;
        }

        @Override
        public void save(final String obj, final NodeSettingsWO settings) {
            // Don't persist - this is a computed field
        }

        @Override
        public String[][] getConfigPaths() {
            return new String[0][];
        }
    }
}
