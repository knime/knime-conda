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
 * Node parameters for the TOML editor dialog. This includes the TOML content and validation messages.
 *
 * @author Marc Lehner, KNIME GmbH, Zurich, Switzerland
 * @author Benjamin Wilhelm, KNIME GmbH, Berlin, Germany
 */
class TomlEditor implements NodeParameters {
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
            knime-python-base = "*"
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
    @ValueReference(TomlContentIsValidRef.class)
    @ValueProvider(TomlContentIsValidProvider.class)
    boolean m_isTomlContentValid = true;

    interface TomlContentIsValidRef extends ParameterReference<Boolean> {
    }

    /**
     * State provider that computes whether the TOML validation message contains an error. This is used to determine if
     * the "Resolve dependencies" button should be enabled.
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