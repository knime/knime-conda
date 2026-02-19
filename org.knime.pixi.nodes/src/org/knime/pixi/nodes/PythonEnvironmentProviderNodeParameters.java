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

import java.util.Optional;
import java.util.function.Supplier;

import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.NodeLogger;
import org.knime.core.node.NodeSettingsRO;
import org.knime.core.node.NodeSettingsWO;
import org.knime.core.webui.node.dialog.defaultdialog.internal.button.SimpleButtonWidget;
import org.knime.core.webui.node.dialog.defaultdialog.internal.dirty.DirtyTracker;
import org.knime.node.parameters.NodeParameters;
import org.knime.node.parameters.NodeParametersInput;
import org.knime.node.parameters.Widget;
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
import org.knime.node.parameters.updates.ValueReference;
import org.knime.node.parameters.widget.choices.Label;
import org.knime.node.parameters.widget.choices.ValueSwitchWidget;
import org.knime.node.parameters.widget.message.TextMessage;
import org.knime.node.parameters.widget.message.TextMessage.MessageType;
import org.knime.pixi.nodes.PackagesArray.InputIsPackagesArray;
import org.knime.pixi.nodes.PackagesArray.PackageArrayRef;
import org.knime.pixi.nodes.TomlEditor.InputIsTomlEditor;
import org.knime.pixi.nodes.TomlEditor.TomlContentIsValidRef;
import org.knime.pixi.nodes.TomlEditor.TomlContentRef;
import org.knime.pixi.nodes.YamlEditor.InputIsYamlEditor;
import org.knime.pixi.nodes.YamlEditor.TomlFromYamlRef;
import org.knime.pixi.nodes.YamlEditor.YamlContentIsValidRef;

/**
 * Node Parameters for the Python Environment Provider node. Combines input methods from array-based, TOML-based, and
 * file reader approaches.
 *
 * @author Marc Lehner, KNIME AG, Zurich, Switzerland
 * @since 5.11
 */
@SuppressWarnings("restriction")
public class PythonEnvironmentProviderNodeParameters implements NodeParameters {

    static final NodeLogger LOGGER = NodeLogger.getLogger("PythonEnvironmentProviderNodeParameters");

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
    static final class ResolveDependenciesButtonSection {
    }

    @After(ResolveDependenciesButtonSection.class)
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
    @Effect(predicate = PackagesArray.InputIsPackagesArray.class, type = EffectType.SHOW)
    PackagesArray m_packagesArray = new PackagesArray();



    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // TOML Editor input
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @Layout(TomlEditorSection.class)
    @Effect(predicate = TomlEditor.InputIsTomlEditor.class, type = EffectType.SHOW)
    TomlEditor m_tomlEditor = new TomlEditor();



    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // YAML Editor input
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @Layout(YamlEditorSection.class)
    @Effect(predicate = YamlEditor.InputIsYamlEditor.class, type = EffectType.SHOW)
    YamlEditor m_yamlEditor = new YamlEditor();



    //////////////////////////////////////////////////////////////////////////////////////////////////////
    // Lock file generation and persistence
    //////////////////////////////////////////////////////////////////////////////////////////////////////

    @Persistor(PixiLockFilePersistor.class)
    @Layout(LockFileSection.class)
    PixiLockFileSettings m_lockFileSettings = new PixiLockFileSettings();

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
            return new String[0][];
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

    //////////////////////////////////////////////////////////////////////////////////////////////////////
    // Resolve dependencies button and lock file generation mechanism
    ///////////////////////////////////////////////////////////////////////////////////////////////////////

    @Widget(title = "Resolve Dependencies", description = """
            Resolve package dependencies and generate lock file.
            This validates that the environment can be created on all configured platforms.
            """)
    @SimpleButtonWidget(ref = ResolveDependenciesButtonRef.class)
    @Layout(ResolveDependenciesButtonSection.class)
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
    @Layout(ResolveDependenciesButtonSection.class)
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



    // Validation message provider
    static final class LockFileStatusMessageProvider implements StateProvider<Optional<TextMessage.Message>> {

        private Supplier<Boolean> m_isCurrentLockUpToDateWithOtherSettingsSupplier;

        @Override
        public void init(final StateProviderInitializer initializer) {
            m_isCurrentLockUpToDateWithOtherSettingsSupplier =
                initializer
                    .computeFromValueSupplier(PixiLockFileSettings.IsCurrentLockUpToDateWithOtherSettingsRef.class);
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
