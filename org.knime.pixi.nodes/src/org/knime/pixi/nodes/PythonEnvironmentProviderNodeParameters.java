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
import org.knime.node.parameters.updates.ValueReference;
import org.knime.node.parameters.widget.choices.Label;
import org.knime.node.parameters.widget.choices.ValueSwitchWidget;
import org.knime.node.parameters.widget.message.TextMessage;
import org.knime.node.parameters.widget.message.TextMessage.MessageType;
import org.knime.pixi.nodes.PixiPackageSpec.PackageSource;
import org.knime.pixi.nodes.TomlEditor.TomlContentIsValidRef;
import org.knime.pixi.nodes.YamlEditor.YamlContentIsValidRef;

/**
 * Node Parameters for the Python Environment Provider node. Combines input methods from array-based, TOML-based, and
 * file reader approaches.
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────────────────────────────┐
 * │                                       USER INPUTS                                        │
 * │                                                                                          │
 * │  m_mainInputSource   m_packages       m_pixiTomlContent     m_envYamlContent             │
 * │  (SIMPLE /           (PixiPackageSpec  (raw pixi.toml        (conda                      │
 * │   TOML_EDITOR /       array)            text)                 environment.yaml)          │
 * │   YAML_EDITOR)                                                                           │
 * │         │                  │                  │                        │                 │
 * │  [MainInputSourceRef] [PackageArrayRef]  [TomlContentRef]       [YamlContentRef]         │
 * └─────────┼──────────────────┼──────────────────┼────────────────────────┼─────────────────┘
 *           │                  │                  │                        │
 *           │                  │   TOML VALIDATION CHAIN                   │
 *           │                  │   ──────────────────────                  │
 *           │                  │                  ▼                        │
 *           │                  │   TomlParseValidationProvider             │
 *           │                  │   (PixiTomlValidator, on change           │
 *           │                  │    + after dialog open)                   │
 *           │                  │           │                               │
 *           │                  │           ├──► \@TextMessage              │
 *           │                  │           │    (TOML parse error/warning) │
 *           │                  │           ▼                               │
 *           │                  │   TomlContentIsValidProvider              │
 *           │                  │           │                               │
 *           │                  │   [TomlContentIsValidRef]─────────────────│───────────┐
 *           │                  │                                           │           │
 *           │                  │          YAML CONVERSION CHAIN            │           │
 *           │                  │          ──────────────────────           │           │
 *           │                  │                                           ▼           │
 *           │                  │                        TomlFromYamlProvider           │
 *           │                  │                        (PixiYamlImporter:             │
 *           │                  │                         pixi init --import)           │
 *           │                  │                                │                      │
 *           │                  │                        [TomlFromYamlRef]              │
 *           │                  │                                │                      │
 *           │                  │                        YamlParseValidationProvider    │
 *           │                  │                        (checks conversion result)     │
 *           │                  │                                │                      │
 *           │                  │                                ├──► \@TextMessage     │
 *           │                  │                                │    (YAML error)      │
 *           │                  │                                ▼                      │
 *           │                  │                        YamlContentIsValidProvider     │
 *           │                  │                                │                      │
 *           │                  │                        [YamlContentIsValidRef]──────────┐
 *           │                  │                                │                      │ │
 *           │    EFFECTIVE TOML AGGREGATION                     │                      │ │
 *           │    ──────────────────────────                     │                      │ │
 *           └────────┬─────────┴──────────────────────────┬─────┘                      │ │
 *                    │   (all refs dispatched by mode)    │                            │ │
 *                    ▼                                    │                            │ │
 *    EffectiveTOMLContentValueProvider◄───────────────────┘                            │ │
 *    (SIMPLE     → PixiPackageSpec.buildPixiTomlFromPackages()                         │ │
 *     TOML_EDITOR → raw TomlContentRef value                                           │ │
 *     YAML_EDITOR → TomlFromYamlRef value)                                             │ │
 *                    │                                                                 │ │
 *           [EffectiveTOMLContentRef]────────────────────────────────────────────────┐ │ │
 *                    │                                                               │ │ │
 *           BUTTON ENABLE PREDICATE                                                  │ │ │
 *           ───────────────────────                                                  │ │ │
 *           ResolveButtonEnabledEffect◄────────────────────────[TomlContentIsValidRef]─┘ │
 *           (SIMPLE:      always enabled ◄───────────────────────[YamlContentIsValidRef]─┘
 *            TOML_EDITOR: enabled if TomlContentIsValidRef = true                    │
 *            YAML_EDITOR: enabled if YamlContentIsValidRef = true)                   │
 *                    │                                                               │
 *                    │ ENABLE                                                        │
 *                    ▼                                                               │
 *          ╔═════════════════════════╗                                               │
 *          ║  Resolve Dependencies   ║                                               │
 *          ║       [ Button ]        ║                                               │
 *          ╚═════════════════════════╝                                               │
 *                    │ click fires ResolveDependenciesButtonRef                      │
 *                    │                                                               │
 *          ┌─────────┴──────────────────────────────────┐                            │
 *          │                                            │                            │
 *          ▼                                            ▼                            │
 *  LockFileProvider                     TomlForLastButtonClickProvider               │
 *  (writes effective TOML               (snapshots current                           │
 *   to temp dir, runs                    [EffectiveTOMLContentRef])                  │
 *   pixi lock, reads                                │                                │
 *   pixi.lock back)                      [TomlForLastButtonClickRef]                 │
 *          │                                            │                            │
 *          ▼                                            │                            │
 *  m_pixiLockFileContent          UP-TO-DATE CHECK      │◄─[EffectiveTOMLContentRef]─┘
 *  (read-only textarea)           ──────────────────────│──────────────────────────────
 *                                 IsCurrentLockUpToDateWithOtherSettingsProvider
 *                                 (effectiveTOML == snapshotTOML ?)
 *                                               │
 *                                 [IsCurrentLockUpToDateWithOtherSettingsRef]
 *                                               │
 *                                 LockFileStatusMessageProvider
 *                                 (false → INFO:    "No lock file generated yet"
 *                                   true → SUCCESS: "Environment validated")
 *                                               │
 *                                               ▼
 *                                         \@TextMessage (status)
 * </pre>
 *
 * @author Marc Lehner, KNIME AG, Zurich, Switzerland
 * @since 5.11
 */
@SuppressWarnings("restriction")
class PythonEnvironmentProviderNodeParameters implements NodeParameters {

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
            @Label("Basic")
            SIMPLE,

            @Label("TOML editor")
            TOML_EDITOR,

            @Label("YAML editor")
            YAML_EDITOR
    }

    @Widget(title = "Input Mode",
        description = "Choose how to define the Python environment (basic package list, raw TOML, or YAML environment input)")
    @Layout(MainInputSelectionSection.class)
    @ValueReference(MainInputSourceRef.class)
    @ValueSwitchWidget
    MainInputSource m_mainInputSource = MainInputSource.SIMPLE;

    interface MainInputSourceRef extends ParameterReference<MainInputSource> {
    }

    // Predicate to show/hide simple (array based) package input
    static final class InputIsPackagesArray implements EffectPredicateProvider {
        @Override
        public EffectPredicate init(final PredicateInitializer i) {
            return i.getEnum(MainInputSourceRef.class).isOneOf(MainInputSource.SIMPLE);
        }
    }

    // Predicate to show/hide TOML editor input
    static final class InputIsTomlEditor implements EffectPredicateProvider {
        @Override
        public EffectPredicate init(final PredicateInitializer i) {
            return i.getEnum(MainInputSourceRef.class).isOneOf(MainInputSource.TOML_EDITOR);
        }
    }

    // Predicate to show/hide YAML editor input
    static final class InputIsYamlEditor implements EffectPredicateProvider {
        @Override
        public EffectPredicate init(final PredicateInitializer i) {
            return i.getEnum(MainInputSourceRef.class).isOneOf(MainInputSource.YAML_EDITOR);
        }
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // PackagesArray input
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @Layout(SimpleInputSection.class)
    @Effect(predicate = InputIsPackagesArray.class, type = EffectType.SHOW)
    @Widget(title = "Packages", description = "Specify the packages to include in the environment")
    @ArrayWidget(elementLayout = ArrayWidget.ElementLayout.VERTICAL_CARD, addButtonText = "Add package")
    @ValueReference(PackageArrayRef.class)
    PixiPackageSpec[] m_packages = new PixiPackageSpec[]{ //
        new PixiPackageSpec("python", PackageSource.CONDA, "3.14"), //
        new PixiPackageSpec("knime-python-base", PackageSource.CONDA, "") // no version constraint for knime-python-base, to always get the latest compatible version with the KNIME version
    };

    interface PackageArrayRef extends ParameterReference<PixiPackageSpec[]> {
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // TOML Editor input
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @Layout(TomlEditorSection.class)
    @Effect(predicate = InputIsTomlEditor.class, type = EffectType.SHOW)
    @Persistor(TomlEditorPersistor.class)
    TomlEditor m_tomlEditor = new TomlEditor();

    static class TomlEditorPersistor implements NodeParametersPersistor<TomlEditor> {

        @Override
        public TomlEditor load(final NodeSettingsRO settings) throws InvalidSettingsException {
            var tomlContent = settings.getString("tomlContent");

            TomlEditor loadedEditor = new TomlEditor();
            loadedEditor.m_pixiTomlContent = tomlContent != null ? tomlContent : "";
            return loadedEditor;
        }

        @Override
        public void save(final TomlEditor param, final NodeSettingsWO settings) {
            settings.addString("tomlContent", param.m_pixiTomlContent);
        }

        @Override
        public String[][] getConfigPaths() {
            return new String[0][];
        }
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // YAML Editor input
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @Layout(YamlEditorSection.class)
    @Effect(predicate = InputIsYamlEditor.class, type = EffectType.SHOW)
    @Persistor(YamlEditorPersistor.class)
    YamlEditor m_yamlEditor = new YamlEditor();

    static class YamlEditorPersistor implements NodeParametersPersistor<YamlEditor> {

        @Override
        public YamlEditor load(final NodeSettingsRO settings) throws InvalidSettingsException {
            var yamlContent = settings.getString("yamlContent");
            var tomlFromYaml = settings.getString("tomlFromYaml");

            YamlEditor loadedEditor = new YamlEditor();
            loadedEditor.m_envYamlContent = yamlContent != null ? yamlContent : "";
            loadedEditor.m_tomlFromYaml = tomlFromYaml != null ? tomlFromYaml : "";
            return loadedEditor;
        }

        @Override
        public void save(final YamlEditor param, final NodeSettingsWO settings) {
            settings.addString("yamlContent", param.m_envYamlContent);
            settings.addString("tomlFromYaml", param.m_tomlFromYaml);
        }

        @Override
        public String[][] getConfigPaths() {
            return new String[0][];
        }
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Lock file generation and persistence
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

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


    // Validation message provider
    static final class LockFileStatusMessageProvider implements StateProvider<Optional<TextMessage.Message>> {

        private Supplier<Boolean> m_isCurrentLockUpToDateWithOtherSettingsSupplier;

        @Override
        public void init(final StateProviderInitializer initializer) {
            m_isCurrentLockUpToDateWithOtherSettingsSupplier = initializer
                .computeFromValueSupplier(PixiLockFileSettings.IsCurrentLockUpToDateWithOtherSettingsRef.class);
        }

        @Override
        public Optional<TextMessage.Message> computeState(final NodeParametersInput context) {
            if (!m_isCurrentLockUpToDateWithOtherSettingsSupplier.get()) {
                LOGGER.debug("No lock file - showing info message");
                return Optional.of(new TextMessage.Message("Lock file status",
                    "Dependencies not resolved. Click 'Resolve Dependencies' to validate the environment and generate the lock file.",
                    MessageType.INFO));
            }

            LOGGER.debug("Lock file present - showing success message");
            // If lock file exists and is not empty, it's valid
            return Optional.of(new TextMessage.Message("Environment validated",
                "Environment resolved successfully. Lock file generated.", MessageType.SUCCESS));
        }
    }
}
