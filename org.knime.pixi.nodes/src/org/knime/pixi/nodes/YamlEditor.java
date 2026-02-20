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
 *   Feb 19, 2026 (Marc Lehner): created
 */
package org.knime.pixi.nodes;

import java.util.Optional;
import java.util.function.Supplier;

import org.knime.node.parameters.NodeParameters;
import org.knime.node.parameters.NodeParametersInput;
import org.knime.node.parameters.Widget;
import org.knime.node.parameters.updates.ParameterReference;
import org.knime.node.parameters.updates.StateProvider;
import org.knime.node.parameters.updates.ValueProvider;
import org.knime.node.parameters.updates.ValueReference;
import org.knime.node.parameters.widget.message.TextMessage;
import org.knime.node.parameters.widget.message.TextMessage.Message;
import org.knime.node.parameters.widget.message.TextMessage.MessageType;
import org.knime.node.parameters.widget.text.TextAreaWidget;

/**
 * Node parameters class that provides a YAML editor for conda environment.yaml files and converts them to pixi.toml
 * format.
 *
 * @author Marc Lehner, KNIME GmbH, Zurich, Switzerland
 * @author Benjamin Wilhelm, KNIME GmbH, Berlin, Germany
 */
class YamlEditor implements NodeParameters {
    @Widget(title = "Environment specification (conda environment.yaml)",
        description = """
                Content of the conda environment.yaml file that describes the environment.
                This will be imported into pixi using `pixi init --import` and converted to a pixi.toml manifest.
                The environment will automatically be configured to work on all major platforms
                (win-64, linux-64, osx-64, osx-arm64).
                """)
    @TextAreaWidget(rows = 20)
    @ValueReference(YamlContentRef.class)
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
            } catch (Exception e) { // NOSONAR - we want to make sure we do not fail
                PythonEnvironmentProviderNodeParameters.LOGGER
                    .error("Failed to convert YAML to TOML: " + e.getMessage(), e);
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
                return Optional.of(new Message("YAML Parse Error", "Could not parse YAML content.", MessageType.ERROR));
            }
            return Optional.empty();
        }
    }

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
