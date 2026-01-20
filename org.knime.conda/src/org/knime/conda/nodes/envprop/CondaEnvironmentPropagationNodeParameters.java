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
 *   Jan 16, 2026 (created): created
 */
package org.knime.conda.nodes.envprop;

import java.util.Optional;
import java.util.function.Supplier;

import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.NodeSettingsRO;
import org.knime.core.node.NodeSettingsWO;
import org.knime.node.parameters.NodeParameters;
import org.knime.node.parameters.NodeParametersInput;
import org.knime.node.parameters.Widget;
import org.knime.node.parameters.persistence.NodeParametersPersistor;
import org.knime.node.parameters.persistence.Persist;
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
import org.knime.node.parameters.widget.choices.RadioButtonsWidget;
import org.knime.node.parameters.widget.message.TextMessage;
import org.knime.node.parameters.widget.message.TextMessage.MessageType;
import org.knime.node.parameters.widget.text.TextAreaWidget;
import org.knime.node.parameters.widget.text.TextInputWidget;

/**
 * Modern UI settings for the Conda Environment Propagation node.
 *
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 */
final class CondaEnvironmentPropagationNodeParameters implements NodeParameters {

    @TextMessage(InfoMessageProvider.class)
    Void m_infoMessage;

    @Widget(title = "Conda Environment Name", //
        description = """
                    This is the name of the conda environment that will be created by conda in its default location,
                matching the name of the captured environment. If an environment with the same name exists, and
                the environment validation is configured accordingly, an existing environment with matching name
                will be used instead of creating a new environment.
                    """)
    @TextInputWidget
    @Effect(predicate = AlwaysTrue.class, type = EffectType.DISABLE)
    @Persist(configKey = CondaEnvironmentPropagationNodeModel.CFG_KEY_CONDA_ENV)
    String m_environmentNameModel = "";

    @Widget(title = "Package environment (Pixi TOML)", //
        description = "This read-only field displays the included packages as a Pixi TOML configuration. "
            + "To modify the environment, copy this TOML and paste it into the Pixi Environment Creator (TOML) node.")
    @TextAreaWidget(rows = 15)
    @Effect(predicate = AlwaysTrue.class, type = EffectType.DISABLE)
    @ValueProvider(PixiTomlDisplayProvider.class)
    @Persistor(PixiTomlPersistor.class)
    String m_pixiTomlDisplay = "";

    @Widget(title = "Environment validation", //
        description = "Determines how the environment is validated during node execution.")
    @RadioButtonsWidget
    @Persistor(ValidationMethodPersistor.class)
    ValidationMethod m_validationMethod = ValidationMethod.NAME_ONLY;

    @Widget(title = "Output variable name", //
        description = "The name of the flow variable that will contain the propagated Conda environment specification.")
    @TextInputWidget
    @Persist(configKey = CondaEnvironmentPropagationNodeModel.CFG_KEY_OUTPUT_VARIABLE_NAME)
    String m_outputVariableName = "conda.environment";

    @Widget(title = "Preserve incomplete environments", //
        description = "If checked, incomplete environments (i.e., environments where not all required packages "
            + "could be installed) will be preserved for inspection. Otherwise, they will be deleted automatically.")
    @Persist(configKey = CondaEnvironmentPropagationNodeModel.CFG_KEY_PRESERVE_INCOMPLETE_ENVS)
    boolean m_preserveIncompleteEnvironments = false;

    @Persistor(CondaPackagesConfigPersistor.class)
    @ValueReference(CondaPackagesConfigRef.class)
    CondaPackagesConfig m_packagesConfig = new CondaPackagesConfig();

    @Persist(configKey = CondaEnvironmentPropagationNodeModel.CFG_KEY_SOURCE_OS_NAME)
    @ValueReference(SourceOperatingSystemRef.class)
    String m_sourceOperatingSystem = "";

    private static class CondaPackagesConfigRef implements ParameterReference<CondaPackagesConfig> {
    }

    private static class SourceOperatingSystemRef implements ParameterReference<String> {
    }

    /**
     * Enum representing the validation method for the Conda environment.
     */
    enum ValidationMethod {
            @Label("Name only")
            NAME_ONLY,

            @Label("Name and packages")
            NAME_AND_PACKAGES,

            @Label("Overwrite")
            OVERWRITE
    }

    /**
     * Predicate that always returns true, used to disable read-only fields.
     */
    static final class AlwaysTrue implements EffectPredicateProvider {
        @Override
        public EffectPredicate init(final PredicateInitializer i) {
            return i.always();
        }
    }

    /**
     * Provider for the info message displayed at the top of the dialog.
     */
    static final class InfoMessageProvider implements StateProvider<Optional<TextMessage.Message>> {

        @Override
        public Optional<TextMessage.Message> computeState(final NodeParametersInput context) {
            return Optional.of(new TextMessage.Message("Read-only mode on Hub",
                "To modify the environment, copy the Pixi TOML below and paste it into the "
                    + "Pixi Environment Creator (TOML) node.",
                MessageType.INFO));
        }

        @Override
        public void init(final StateProviderInitializer initializer) {
            initializer.computeBeforeOpenDialog();
        }
    }

    /**
     * StateProvider that computes the TOML display from packages and source OS.
     */
    static final class PixiTomlDisplayProvider implements StateProvider<String> {

        private Supplier<CondaPackagesConfig> m_condaPackagesConfigProvider;

        private Supplier<String> m_sourceOperatingSystemProvider;

        @Override
        public String computeState(final NodeParametersInput context) {
            var packageConfig = m_condaPackagesConfigProvider.get();
            return CondaPackageToTomlConverter.convertToPixiToml(
                packageConfig.getIncludedPackages(), m_sourceOperatingSystemProvider.get());
        }

        @Override
        public void init(final StateProviderInitializer initializer) {
            initializer.computeBeforeOpenDialog();
            m_condaPackagesConfigProvider = initializer.getValueSupplier(CondaPackagesConfigRef.class);
            m_sourceOperatingSystemProvider = initializer.getValueSupplier(SourceOperatingSystemRef.class);
        }
    }

    /**
     * Custom persistor for validation method enum to string conversion.
     */
    static final class ValidationMethodPersistor implements NodeParametersPersistor<ValidationMethod> {
        @Override
        public ValidationMethod load(final NodeSettingsRO settings) throws InvalidSettingsException {
            final String validationMethodStr =
                settings.getString(CondaEnvironmentPropagationNodeModel.CFG_KEY_ENV_VALIDATION_METHOD,
                    CondaEnvironmentPropagationNodeModel.VALIDATION_METHOD_NAME);
            return switch (validationMethodStr) {
                case CondaEnvironmentPropagationNodeModel.VALIDATION_METHOD_NAME -> ValidationMethod.NAME_ONLY;
                case CondaEnvironmentPropagationNodeModel.VALIDATION_METHOD_NAME_PACKAGES -> ValidationMethod.NAME_AND_PACKAGES;
                case CondaEnvironmentPropagationNodeModel.VALIDATION_METHOD_OVERWRITE -> ValidationMethod.OVERWRITE;
                default -> throw new InvalidSettingsException("Unknown validation method: " + validationMethodStr);
            };
        }

        @Override
        public void save(final ValidationMethod obj, final NodeSettingsWO settings) {
            final String validationMethodStr = switch (obj) {
                case NAME_ONLY -> CondaEnvironmentPropagationNodeModel.VALIDATION_METHOD_NAME;
                case NAME_AND_PACKAGES -> CondaEnvironmentPropagationNodeModel.VALIDATION_METHOD_NAME_PACKAGES;
                case OVERWRITE -> CondaEnvironmentPropagationNodeModel.VALIDATION_METHOD_OVERWRITE;
            };
            settings.addString(CondaEnvironmentPropagationNodeModel.CFG_KEY_ENV_VALIDATION_METHOD, validationMethodStr);
        }

        @Override
        public String[][] getConfigPaths() {
            return new String[][]{{CondaEnvironmentPropagationNodeModel.CFG_KEY_ENV_VALIDATION_METHOD}};
        }
    }

    /**
     * Custom persistor for CondaPackagesConfig.
     */
    static final class CondaPackagesConfigPersistor implements NodeParametersPersistor<CondaPackagesConfig> {
        @Override
        public CondaPackagesConfig load(final NodeSettingsRO settings) throws InvalidSettingsException {
            final CondaPackagesConfig config = new CondaPackagesConfig();
            config.loadSettingsFrom(settings);
            return config;
        }

        @Override
        public void save(final CondaPackagesConfig obj, final NodeSettingsWO settings) {
            obj.saveSettingsTo(settings);
        }

        @Override
        public String[][] getConfigPaths() {
            return new String[][]{{"included_packages"}, {"excluded_packages"}};
        }
    }

    /**
     * Custom persistor that doesn't load or save anything, for the TOML that is computed from other values.
     */
    static final class PixiTomlPersistor implements NodeParametersPersistor<String> {
        private static final String CFG_KEY = "generated_pixi_toml";

        @Override
        public String load(final NodeSettingsRO settings) throws InvalidSettingsException {
            return settings.getString(CFG_KEY, null);
        }

        @Override
        public void save(final String obj, final NodeSettingsWO settings) {
            settings.addString(CFG_KEY, obj);
        }

        @Override
        public String[][] getConfigPaths() {
            return new String[0][];
        }
    }

}
