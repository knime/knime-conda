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

import org.knime.core.node.InvalidSettingsException;
import org.knime.node.DefaultModel.ConfigureInput;
import org.knime.node.DefaultModel.ConfigureOutput;
import org.knime.node.DefaultModel.ExecuteInput;
import org.knime.node.DefaultModel.ExecuteOutput;
import org.knime.node.DefaultNode;
import org.knime.node.DefaultNodeFactory;
import org.knime.pixi.port.PythonEnvironmentPortObject;
import org.knime.pixi.port.PythonEnvironmentPortObjectSpec;

/**
 * The Python Environment Provider node.
 *
 * @author Marc Lehner, KNIME GmbH, Zurich, Switzerland
 * @since 5.11.0
 */
public final class PythonEnvironmentProviderNodeFactory extends DefaultNodeFactory {

    /**
     * Constructor
     */
    public PythonEnvironmentProviderNodeFactory() {
        super(buildNodeDefinition());
    }

    private static DefaultNode buildNodeDefinition() {
        return DefaultNode.create().name("Python Environment Provider (Preview)") //
            .icon("python.png") //
            .shortDescription("Provides a Python environment to downstream nodes") //
            .fullDescription( //
                """
                        <p>
                        This node provides a Python environment specification to downstream Python-aware nodes such as
                        Python Script nodes. The environment is defined using <a href="https://pixi.sh">pixi</a>, a
                        fast package manager built on top of conda-forge and PyPI that ensures reproducible Python
                        environments across different platforms (Windows, Linux, macOS).
                        </p>
                        <p>
                        The node offers three ways to specify the Python environment:
                        </p>
                        <ul>
                        <li><b>Packages:</b> Define packages directly in a simple table format. Specify package names,
                        sources (Conda or PyPI), and optional version constraints. This is the easiest method for
                        simple environments.</li>
                        <li><b>TOML editor:</b> Manually write or edit a complete pixi.toml manifest file. This provides
                        full control over all pixi features including channels, platforms, and complex dependency
                        specifications. The TOML format follows the <a href="https://pixi.sh/latest/reference/project_configuration/">pixi project specification</a>.
                        <br/><b>Example TOML:</b>
                        <pre>[workspace]
                        channels = ["knime", "conda-forge"]
                        platforms = ["win-64", "linux-64", "osx-64", "osx-arm64"]

                        [dependencies]
                        python = "3.13.*"
                        knime-python-base = "*"</pre>
                        </li>
                        <li><b>YAML editor:</b> Import an existing conda environment.yaml file. The node automatically
                        converts this to pixi format and configures it for all major platforms (Windows, Linux, macOS Intel/ARM).</li>
                        </ul>
                        <p>
                        <b>Environment resolution:</b> After specifying the environment using any of the above methods,
                                                click the "Resolve Dependencies" button. This runs pixi to resolve all package dependencies
                        and verify that the environment can be created on all configured platforms. The resolved
                        environment is stored in a lock file, ensuring that the exact same package versions are used
                        regardless of when or where the workflow is executed.
                        </p>
                        <p>
                        <b>Using the environment:</b> The output port of this node provides a Python Environment
                        specification that can be connected to Python Script nodes and other Python-aware KNIME nodes.
                        These downstream nodes will automatically use the specified environment when executing Python code.
                        </p>
                        <p>
                        <b>Note:</b> The actual Python environment is not created by this node. Instead, downstream
                        Python nodes will automatically create and cache the environment on first use based on the
                        lock file specification. In the `conda` prefernce paga you can configure where the environments should be created and cached.
                        By default, they are stored in temporary directories and automatically cleaned up. But you can also choose to store them in a fixed location.
                        </p>""") //
            .sinceVersion(5, 11, 0)
            .ports(p -> p.addOutputPort("Python Environment",
                "Python environment information (pixi.toml and pixi.lock content)",
                PythonEnvironmentPortObject.TYPE))
            .model(modelStage -> modelStage //
                .parametersClass(PythonEnvironmentProviderNodeParameters.class) //
                .configure(PythonEnvironmentProviderNodeFactory::configure) //
                .execute(PythonEnvironmentProviderNodeFactory::execute)) //
            .keywords("pixi", "python", "environment", "conda", "pip", "packages"); //
    }

    private static void configure(final ConfigureInput in, final ConfigureOutput out) throws InvalidSettingsException {
        final PythonEnvironmentProviderNodeParameters params = in.getParameters();

        var lockFile = params.m_lockFileSettings.m_pixiLockFileContent;
        if (lockFile == null || lockFile.isBlank()) {
            throw new InvalidSettingsException("Python environment is not resolved. "
                + "Press the \"Resolve Dependencies\" button to resolve the environment.");
        }

        // Set the spec for the output port
        out.setOutSpecs(PythonEnvironmentPortObjectSpec.INSTANCE);
    }

    private static void execute(final ExecuteInput in, final ExecuteOutput out) {
        final PythonEnvironmentProviderNodeParameters params = in.getParameters();

        final var portObject = new PythonEnvironmentPortObject( //
            params.m_lockFileSettings.m_tomlForLastLockFileGeneration, //
            params.m_lockFileSettings.m_pixiLockFileContent //
        );
        out.setOutData(portObject);
    }

}
