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

import org.knime.node.DefaultModel.ConfigureInput;
import org.knime.node.DefaultModel.ConfigureOutput;
import org.knime.node.DefaultModel.ExecuteInput;
import org.knime.node.DefaultModel.ExecuteOutput;
import org.knime.node.DefaultNode;
import org.knime.node.DefaultNodeFactory;
import org.knime.pixi.port.PythonEnvironmentPortObject;
import org.knime.pixi.port.PythonEnvironmentPortObjectSpec;

/**
 *
 * @author Marc Lehner
 * @since 5.10
 */
public final class PythonEnvironmentProviderNodeFactory extends DefaultNodeFactory {

    /**
     * Constructor
     */
    public PythonEnvironmentProviderNodeFactory() {
        super(buildNodeDefinition());
    }

    private static DefaultNode buildNodeDefinition() {
        return DefaultNode.create().name("Python Environment Provider (Labs)") //
            .icon("icon.png") //
            .shortDescription("Provides a Python environment to downstream nodes") //
            .fullDescription( //
                """
                        Provides a Python environment to downstream nodes.
                        """) //
            .sinceVersion(5, 10, 0).ports(p -> {
                p.addOutputPort("Pixi Environment", "Pixi Python environment information",
                    PythonEnvironmentPortObject.TYPE);
            }).model(modelStage -> modelStage //
                .parametersClass(PythonEnvironmentProviderNodeParameters.class) //
                .configure(PythonEnvironmentProviderNodeFactory::configure) //
                .execute(PythonEnvironmentProviderNodeFactory::execute)) //
            .keywords("pixi", "python", "environment", "conda", "pip", "packages"); //
    }

    private static void configure(final ConfigureInput in, final ConfigureOutput out) {
        // Set the spec for the output port
        out.setOutSpecs(PythonEnvironmentPortObjectSpec.INSTANCE);
    }

    private static void execute(final ExecuteInput in, final ExecuteOutput out) {
        final PythonEnvironmentProviderNodeParameters params = in.getParameters();
        final var execCtx = in.getExecutionContext();

        try {
            final String pixiLockContent = params.getPixiLockFileContent();
            final String pixiTomlContent = params.getPixiTomlFileContent();

            // Validate that we have both TOML and lock file content
            if (pixiTomlContent == null || pixiTomlContent.isBlank()) {
                throw new IllegalStateException("TOML content is empty. Please configure the environment.");
            }
            if (pixiLockContent == null || pixiLockContent.isBlank()) {
                throw new IllegalStateException(
                    "Lock file content is empty. Please click 'Check compatibility' to generate the lock file.");
            }

            // Create and output the Python environment port object
            final PythonEnvironmentPortObject portObject =
                new PythonEnvironmentPortObject(pixiTomlContent, pixiLockContent);
            out.setOutData(portObject);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read environment configuration: " + e.getMessage(), e);
        }
    }

}
