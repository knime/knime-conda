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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.knime.conda.envinstall.pixi.PixiBinary;
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.NodeLogger;
import org.knime.core.node.NodeSettingsRO;
import org.knime.core.node.NodeSettingsWO;
import org.knime.core.util.PathUtils;
import org.knime.core.webui.node.dialog.defaultdialog.internal.button.SimpleButtonWidget;
import org.knime.core.webui.node.dialog.defaultdialog.internal.dirty.DirtyTracker;
import org.knime.core.webui.node.dialog.defaultdialog.util.updates.StateComputationFailureException;
import org.knime.core.webui.node.dialog.defaultdialog.widget.handler.WidgetHandlerException;
import org.knime.node.parameters.NodeParameters;
import org.knime.node.parameters.NodeParametersInput;
import org.knime.node.parameters.Widget;
import org.knime.node.parameters.WidgetGroup;
import org.knime.node.parameters.array.ArrayWidget;
import org.knime.node.parameters.layout.After;
import org.knime.node.parameters.layout.Layout;
import org.knime.node.parameters.persistence.NodeParametersPersistor;
import org.knime.node.parameters.persistence.Persistor;
import org.knime.node.parameters.updates.ButtonReference;
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
import org.knime.node.parameters.widget.message.TextMessage.Message;
import org.knime.node.parameters.widget.message.TextMessage.MessageType;
import org.knime.node.parameters.widget.text.TextAreaWidget;
import org.knime.pixi.nodes.PixiPackageSpec.PackageSource;
import org.knime.pixi.nodes.PythonEnvironmentProviderNodeParameters.PackagesArray.InputIsPackagesArray;
import org.knime.pixi.nodes.PythonEnvironmentProviderNodeParameters.PackagesArray.PackageArrayRef;
import org.knime.pixi.nodes.PythonEnvironmentProviderNodeParameters.PixiLockFileSettings.IsCurrentLockUpToDateWithOtherSettingsRef;
import org.knime.pixi.nodes.PythonEnvironmentProviderNodeParameters.TomlEditor.InputIsTomlEditor;
import org.knime.pixi.nodes.PythonEnvironmentProviderNodeParameters.TomlEditor.TomlContentIsValidRef;
import org.knime.pixi.nodes.PythonEnvironmentProviderNodeParameters.TomlEditor.TomlContentRef;
import org.knime.pixi.nodes.PythonEnvironmentProviderNodeParameters.YamlEditor.InputIsYamlEditor;
import org.knime.pixi.nodes.PythonEnvironmentProviderNodeParameters.YamlEditor.TomlFromYamlRef;
import org.knime.pixi.nodes.PythonEnvironmentProviderNodeParameters.YamlEditor.YamlContentIsValidRef;
import org.knime.pixi.port.PixiUtils;

/**
 * Node Parameters for the Python Environment Provider node. Combines input methods from array-based, TOML-based, and
 * file reader approaches.
 *
 * @author Marc Lehner, KNIME AG, Zurich, Switzerland
 * @since 5.11
 */
@SuppressWarnings("restriction")
public class PythonEnvironmentProviderNodeParameters implements NodeParameters {

    private static final NodeLogger LOGGER = NodeLogger.getLogger("PythonEnvironmentProviderNodeParameters");

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Layout sections
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    static final class MainInputSelectionSection {
    }

    @After(MainInputSelectionSection.class)
    static final class SimpleInputSection {
    }

    @After(MainInputSelectionSection.class)
    static final class TomlEditorSection {
    }

    @After(MainInputSelectionSection.class)
    static final class YamlEditorSection {
    }

    @After(SimpleInputSection.class)
    @After(TomlEditorSection.class)
    @After(YamlEditorSection.class)
    static final class LockFileSection {
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Input source selection
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    enum MainInputSource {
            @Label("Packages")
            SIMPLE,

            @Label("TOML editor")
            TOML_EDITOR,

            @Label("YAML editor")
            YAML_EDITOR
    }

    @Widget(title = "Input source", description = "Choose how to define the Python environment")
    @Layout(MainInputSelectionSection.class)
    @ValueReference(MainInputSourceRef.class)
    @ValueSwitchWidget
    MainInputSource m_mainInputSource = MainInputSource.SIMPLE;

    interface MainInputSourceRef extends ParameterReference<MainInputSource> {
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // PackagesArray input
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @Layout(SimpleInputSection.class)
    @Effect(predicate = InputIsPackagesArray.class, type = EffectType.SHOW)
    PackagesArray m_packagesArray = new PackagesArray();

    static class PackagesArray implements NodeParameters {

        // Predicate to show/hide simple (array based) package input
        static final class InputIsPackagesArray implements EffectPredicateProvider {
            @Override
            public EffectPredicate init(final PredicateInitializer i) {
                return i.getEnum(MainInputSourceRef.class).isOneOf(MainInputSource.SIMPLE);
            }
        }

        // Simple/Packages input
        @Widget(title = "Packages", description = "Specify the packages to include in the environment")
        @ArrayWidget(elementLayout = ArrayWidget.ElementLayout.HORIZONTAL_SINGLE_LINE, addButtonText = "Add package")
        @ValueReference(PackageArrayRef.class)
        PixiPackageSpec[] m_packages = new PixiPackageSpec[]{ //
            new PixiPackageSpec("python", PackageSource.CONDA, "3.14", "3.14"), //
            new PixiPackageSpec("knime-python-base", PackageSource.CONDA, "5.9", "5.9") //
        };

        interface PackageArrayRef extends ParameterReference<PixiPackageSpec[]> {
        }
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // TOML Editor input
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @Layout(TomlEditorSection.class)
    @Effect(predicate = InputIsTomlEditor.class, type = EffectType.SHOW)
    TomlEditor m_tomlEditor = new TomlEditor();

    static class TomlEditor implements NodeParameters {

        // Predicate to show/hide TOML editor input
        static final class InputIsTomlEditor implements EffectPredicateProvider {
            @Override
            public EffectPredicate init(final PredicateInitializer i) {
                return i.getEnum(MainInputSourceRef.class).isOneOf(MainInputSource.TOML_EDITOR);
            }
        }

        // TOML editor input

        @Widget(title = "Environment specification (pixi.toml)", description = """
                Content of the pixi.toml manifest file that describes the environment.
                """)
        @TextAreaWidget(rows = 20)
        @ValueReference(TomlContentRef.class)
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

        // Validation message for TOML content (error if parsing fails, warning if platforms are not valid)

        @TextMessage(TomlParseValidationProvider.class)
        Void m_tomlParseValidationMessage;

        /**
         * State provider for platform validation message.
         */
        static final class TomlParseValidationProvider implements StateProvider<Optional<Message>> {

            private Supplier<String> m_tomlContentSupplier;

            @Override
            public void init(final StateProviderInitializer initializer) {
                initializer.computeOnValueChange(TomlContentRef.class);
                initializer.computeAfterOpenDialog();
                m_tomlContentSupplier = initializer.getValueSupplier(TomlContentRef.class);
            }

            @Override
            public Optional<TextMessage.Message> computeState(final NodeParametersInput context) {
                final String tomlContent = m_tomlContentSupplier.get();
                return PixiTomlValidator.toMessage(PixiTomlValidator.validatePlatforms(tomlContent));
            }
        }

        // Reference to store whether the TOML content is valid (no errors, platforms valid).
        // This is used to enable/disable the resolve button and show messages.

        // TODO do not persist?
        @ValueReference(TomlContentIsValidRef.class)
        @ValueProvider(TomlContentIsValidProvider.class)
        boolean m_isTomlContentValid = true;

        interface TomlContentIsValidRef extends ParameterReference<Boolean> {
        }

        /**
         * State provider that computes whether the TOML validation message contains an error. This is used to determine
         * if the "Resolve dependencies" button should be enabled.
         */
        static final class TomlContentIsValidProvider implements StateProvider<Boolean> {

            private Supplier<Optional<Message>> m_validationMessage;

            @Override
            public void init(final StateProviderInitializer initializer) {
                m_validationMessage = initializer.computeFromProvidedState(TomlParseValidationProvider.class);
            }

            @Override
            public Boolean computeState(final NodeParametersInput context) {
                return m_validationMessage.get().map(m -> m.type() != MessageType.ERROR).orElse(true);
            }
        }
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // YAML Editor input
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @Layout(YamlEditorSection.class)
    @Effect(predicate = InputIsYamlEditor.class, type = EffectType.SHOW)
    YamlEditor m_yamlEditor = new YamlEditor();

    static class YamlEditor implements NodeParameters {

        // Predicate to show/hide YAML editor input
        static final class InputIsYamlEditor implements EffectPredicateProvider {
            @Override
            public EffectPredicate init(final PredicateInitializer i) {
                return i.getEnum(MainInputSourceRef.class).isOneOf(MainInputSource.YAML_EDITOR);
            }
        }

        @Widget(title = "Environment specification (conda environment.yaml)",
            description = """
                    Content of the conda environment.yaml file that describes the environment.
                    This will be imported into pixi using `pixi init --import` and converted to a pixi.toml manifest.
                    The environment will automatically be configured to work on all major platforms (win-64, linux-64, osx-64, osx-arm64).
                    """)
        @TextAreaWidget(rows = 20)
        @ValueReference(YamlContentRef.class)
        @Effect(predicate = InputIsYamlEditor.class, type = EffectType.SHOW)
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

        @ValueProvider(TomlFromYamlProvider.class)
        @ValueReference(TomlFromYamlRef.class)
        String m_tomlFromYaml = "ERROR";

        interface TomlFromYamlRef extends ParameterReference<String> {
        }

        static class TomlFromYamlProvider implements StateProvider<String> {

            private Supplier<String> m_yamlContentSupplier;

            @Override
            public void init(final StateProviderInitializer initializer) {
                m_yamlContentSupplier = initializer.computeFromValueSupplier(YamlContentRef.class);
            }

            @Override
            public String computeState(final NodeParametersInput context) {
                try {
                    return PixiYamlImporter.convertYamlToToml(m_yamlContentSupplier.get());
                } catch (Exception e) {
                    LOGGER.error("Failed to convert YAML to TOML: " + e.getMessage(), e);
                    return "ERROR";
                }
            }
        }

        @TextMessage(YamlParseValidationProvider.class)
        Void m_yamlParseValidationMessage;

        static final class YamlParseValidationProvider implements StateProvider<Optional<Message>> {

            private Supplier<String> m_tomlFromYamlSupplier;

            @Override
            public void init(final StateProviderInitializer initializer) {
                m_tomlFromYamlSupplier = initializer.computeFromValueSupplier(TomlFromYamlRef.class);
            }

            @Override
            public Optional<Message> computeState(final NodeParametersInput context) {
                if (m_tomlFromYamlSupplier.get() == null || m_tomlFromYamlSupplier.get().isBlank()
                    || m_tomlFromYamlSupplier.get().equals("ERROR")) {
                    return Optional
                        .of(new Message("YAML Parse Error", "Could not parse YAML content.", MessageType.ERROR));
                }
                return Optional.empty();
            }
        }

        // TODO do not persist?
        @ValueReference(YamlContentIsValidRef.class)
        @ValueProvider(YamlContentIsValidProvider.class)
        boolean m_isYamlContentValid = true;

        interface YamlContentIsValidRef extends ParameterReference<Boolean> {
        }

        /** State provider that computes whether the YAML validation message shows an error. */
        static final class YamlContentIsValidProvider implements StateProvider<Boolean> {

            private Supplier<Optional<Message>> m_validationMessage;

            @Override
            public void init(final StateProviderInitializer initializer) {
                m_validationMessage = initializer.computeFromProvidedState(YamlParseValidationProvider.class);
            }

            @Override
            public Boolean computeState(final NodeParametersInput context) {
                return m_validationMessage.get().map(m -> m.type() != MessageType.ERROR).orElse(true);
            }
        }
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////
    // Lock file generation and persistence
    //////////////////////////////////////////////////////////////////////////////////////////////////////

    @Persistor(PixiLockFilePersistor.class)
    PixiLockFileSettings m_lockFileSettings = new PixiLockFileSettings();

    static class PixiLockFileSettings implements WidgetGroup {

        @ValueReference(EffectiveTOMLContentRef.class)
        @ValueProvider(EffectiveTOMLContentValueProvider.class)
        String m_effectiveTOMLContent = "";

        @ValueProvider(TomlForLastButtonClickProvider.class)
        @ValueReference(TomlForLastButtonClickRef.class)
        String m_tomlForLastLockFileGeneration = "";

        @ValueProvider(LockFileProvider.class)
        @Widget(title = "Lock file content", description = "Content of the generated or loaded pixi.lock file.",
            advanced = true)
        @Layout(LockFileSection.class)
        @TextAreaWidget(rows = 10)
        String m_pixiLockFileContent = "";

        @ValueReference(IsCurrentLockUpToDateWithOtherSettingsRef.class)
        @ValueProvider(IsCurrentLockUpToDateWithOtherSettingsProvider.class)
        boolean m_isCurrentLockUpToDateWithOtherSettings = false;

        interface IsCurrentLockUpToDateWithOtherSettingsRef extends ParameterReference<Boolean> {
        }

        static class IsCurrentLockUpToDateWithOtherSettingsProvider implements StateProvider<Boolean> {

            private Supplier<String> m_effectiveTOMLContentSupplier;

            private Supplier<String> m_tomlForLastButtonClickSupplier;

            @Override
            public void init(final StateProviderInitializer initializer) {
                m_effectiveTOMLContentSupplier = initializer.computeFromValueSupplier(EffectiveTOMLContentRef.class);
                m_tomlForLastButtonClickSupplier =
                    initializer.computeFromValueSupplier(TomlForLastButtonClickRef.class);
            }

            @Override
            public Boolean computeState(final NodeParametersInput context) {
                return Objects.equals(m_effectiveTOMLContentSupplier.get(), m_tomlForLastButtonClickSupplier.get());
            }
        }
    }

    static class PixiLockFilePersistor implements NodeParametersPersistor<PixiLockFileSettings> {

        @Override
        public PixiLockFileSettings load(final NodeSettingsRO settings) throws InvalidSettingsException {
            var lockFileContent = settings.getString("pixiLockFileContent");
            var tomlForLastGeneration = settings.getString("tomlForLastLockFileGeneration");

            if (lockFileContent != null && !lockFileContent.isBlank()) {
                PixiLockFileSettings loadedSettings = new PixiLockFileSettings();
                loadedSettings.m_pixiLockFileContent = lockFileContent;
                loadedSettings.m_isCurrentLockUpToDateWithOtherSettings = true;
                loadedSettings.m_tomlForLastLockFileGeneration = tomlForLastGeneration;
                return loadedSettings;
            } else {
                // No lock file content - return default settings with empty lock file and not up to date
                return new PixiLockFileSettings();
            }
        }

        @Override
        public void save(final PixiLockFileSettings param, final NodeSettingsWO settings) {
            if (param.m_isCurrentLockUpToDateWithOtherSettings) {
                settings.addString("pixiLockFileContent", param.m_pixiLockFileContent);
                settings.addString("tomlForLastLockFileGeneration", param.m_tomlForLastLockFileGeneration);
            } else {
                settings.addString("pixiLockFileContent", "");
                settings.addString("tomlForLastLockFileGeneration", "");
            }
        }

        @Override
        public String[][] getConfigPaths() {
            // TODO
            return null;
        }
    }

    interface TomlForLastButtonClickRef extends ParameterReference<String> {
    }

    /** Copies effective TOML on button click */
    static class TomlForLastButtonClickProvider implements StateProvider<String> {

        private Supplier<String> m_effectiveTOMLContentSupplier;

        @Override
        public void init(final StateProviderInitializer initializer) {
            initializer.computeOnButtonClick(ResolveDependenciesButtonRef.class);
            m_effectiveTOMLContentSupplier = initializer.getValueSupplier(EffectiveTOMLContentRef.class);
        }

        @Override
        public String computeState(final NodeParametersInput context) {
            return m_effectiveTOMLContentSupplier.get();
        }
    }

    interface EffectiveTOMLContentRef extends ParameterReference<String> {
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////
    // Resolve dependencies button and lock file generation mechanism
    ///////////////////////////////////////////////////////////////////////////////////////////////////////

    @Widget(title = "Resolve Dependencies", description = """
            Resolve package dependencies and generate lock file.
            This validates that the environment can be created on all configured platforms.
            """)
    @SimpleButtonWidget(ref = ResolveDependenciesButtonRef.class)
    @Layout(LockFileSection.class)
    @Effect(predicate = ResolveButtonEnabledEffect.class, type = EffectType.ENABLE)
    Void m_resolveDependenciesButton;

    static class ResolveButtonEnabledEffect implements EffectPredicateProvider {
        @Override
        public EffectPredicate init(final PredicateInitializer i) {
            return i.getPredicate(InputIsPackagesArray.class) //
                .or(i.getPredicate(InputIsTomlEditor.class).and(i.getBoolean(TomlContentIsValidRef.class).isTrue())) //
                .or(i.getPredicate(InputIsYamlEditor.class).and(i.getBoolean(YamlContentIsValidRef.class).isTrue())); //
        }
    }

    static final class ResolveDependenciesButtonRef implements ButtonReference {
    }

    @DirtyTracker(DirtyOnButtonClickProvider.class)
    Void dirtyOnButtonClick;

    static class DirtyOnButtonClickProvider implements StateProvider<Boolean> {

        @Override
        public void init(final StateProviderInitializer initializer) {
            initializer.computeOnButtonClick(ResolveDependenciesButtonRef.class);
        }

        @Override
        public Boolean computeState(final NodeParametersInput context) {
            return true;
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////
    // Messages and Satus indicators
    ///////////////////////////////////////////////////////////////////////////////////////////////////////

    @TextMessage(LockFileStatusMessageProvider.class)
    @Layout(LockFileSection.class)
    Void m_lockFileStatusMessage;

    static final class EffectiveTOMLContentValueProvider implements StateProvider<String> {

        private Supplier<MainInputSource> m_inputSourceSupplier;

        private Supplier<PixiPackageSpec[]> m_packagesSupplier;

        private Supplier<String> m_tomlContentSupplier;

        private Supplier<String> m_tomlFromYamlSupplier;

        @Override
        public void init(final StateProviderInitializer initializer) {
            initializer.computeBeforeOpenDialog();
            m_inputSourceSupplier = initializer.computeFromValueSupplier(MainInputSourceRef.class);
            m_packagesSupplier = initializer.computeFromValueSupplier(PackageArrayRef.class);
            m_tomlContentSupplier = initializer.computeFromValueSupplier(TomlContentRef.class);
            m_tomlFromYamlSupplier = initializer.computeFromValueSupplier(TomlFromYamlRef.class);
        }

        @Override
        public String computeState(final NodeParametersInput context) {
            return PixiManifestResolver.getTomlContent(m_inputSourceSupplier.get(), m_packagesSupplier.get(),
                m_tomlContentSupplier.get(), m_tomlFromYamlSupplier.get(), LOGGER);
        }
    }

    static final class EffectiveTOMLContentDirtyStateProvider implements StateProvider<Boolean> {

        private Supplier<String> m_effectiveTOMLContentSupplier;

        @Override
        public void init(final StateProviderInitializer initializer) {
            m_effectiveTOMLContentSupplier = initializer.computeFromValueSupplier(EffectiveTOMLContentRef.class);
        }

        @Override
        public Boolean computeState(final NodeParametersInput context) {
            final String effectiveToml = m_effectiveTOMLContentSupplier.get();
            return effectiveToml != null && !effectiveToml.isBlank();
        }
    }

    /**
     * Resets lock file to empty when any input content changes.
     */
    static final class LockFileProvider implements StateProvider<String> {

        private Supplier<String> m_effectiveTOMLContentSupplier;

        @Override
        public void init(final StateProviderInitializer initializer) {
            initializer.computeOnButtonClick(ResolveDependenciesButtonRef.class);
            m_effectiveTOMLContentSupplier = initializer.getValueSupplier(EffectiveTOMLContentRef.class);
        }

        @Override
        public String computeState(final NodeParametersInput context) throws StateComputationFailureException {
            Path projectDir = null;
            try {

                // Always use a fresh temp directory during configuration
                projectDir = PathUtils.createTempDir("pixi-envs-config");

                // Write the TOML manifest to the temp directory
                final Path tomlFilePath = projectDir.resolve("pixi.toml");
                Files.writeString(tomlFilePath, m_effectiveTOMLContentSupplier.get());

                // Run pixi lock to resolve dependencies and generate lock file
                final String[] pixiArgs = {"--color", "never", "--no-progress", "lock"};
                final var callResult = PixiBinary.callPixiWithCancellation(projectDir, null, () -> false, pixiArgs);

                if (callResult.returnCode() != 0) {
                    String errorDetails = PixiUtils.getMessageFromCallResult(callResult);
                    throw new WidgetHandlerException("Pixi lock failed:\n" + errorDetails);
                }

                // Read the generated lock file
                final Path lockFilePath = projectDir.resolve("pixi.lock");
                if (Files.exists(lockFilePath)) {
                    final String lockContent = Files.readString(lockFilePath);
                    LOGGER.warn("Lock file generated with " + lockContent.length() + " chars");
                    return lockContent;
                } else {
                    throw new WidgetHandlerException("Lock file was not generated at: " + lockFilePath);
                }
            } catch (WidgetHandlerException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WidgetHandlerException("Lock generation was cancelled");
            } catch (Exception ex) {
                throw new WidgetHandlerException("Failed to generate lock file: " + ex.getMessage());
            } finally {
                // Clean up temp directory
                try {
                    PathUtils.deleteDirectoryIfExists(projectDir);
                } catch (Exception e) {
                    // Best effort cleanup - log but don't fail
                }
            }
        }
    }

    // Validation message provider
    static final class LockFileStatusMessageProvider implements StateProvider<Optional<TextMessage.Message>> {

        private Supplier<Boolean> m_isCurrentLockUpToDateWithOtherSettingsSupplier;

        @Override
        public void init(final StateProviderInitializer initializer) {
            m_isCurrentLockUpToDateWithOtherSettingsSupplier =
                initializer.computeFromValueSupplier(IsCurrentLockUpToDateWithOtherSettingsRef.class);
        }

        @Override
        public Optional<TextMessage.Message> computeState(final NodeParametersInput context) {
            if (!m_isCurrentLockUpToDateWithOtherSettingsSupplier.get()) {
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
}
