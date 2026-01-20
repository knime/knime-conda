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

import org.knime.conda.envbundling.environment.CondaEnvironmentRegistry;
import org.knime.core.node.NodeLogger;
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

    private static final NodeLogger LOGGER = NodeLogger.getLogger(PythonEnvironmentProviderNodeFactory.class);

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
            final String pixiTomlContent = params.getPixiTomlFileContent();

            // Validate that we have TOML content
            if (pixiTomlContent == null || pixiTomlContent.isBlank()) {
                throw new IllegalStateException("TOML content is empty. Please configure the environment.");
            }

            // Handle different input modes
            final PythonEnvironmentPortObject portObject;
            switch (params.m_mainInputSource) {
                case SIMPLE:
                case TOML_EDITOR:
                case YAML_EDITOR:
                    // For generated/edited TOML/YAML, get lock file (may be empty - that's ok)
                    final String pixiLockContent = params.getPixiLockFileContent();
                    final String lockToUse = (pixiLockContent != null) ? pixiLockContent : "";
                    if (lockToUse.isBlank()) {
                        LOGGER.debug("No lock file - proceeding without lock (may fail on installation)");
                    }
                    portObject = new PythonEnvironmentPortObject(pixiTomlContent, lockToUse);
                    break;

                case TOML_FILE:
                case BUNDLING_ENVIRONMENT:
                    // For file-based or bundled input, determine the project directory (pixi root)
                    final Path projectDir;
                    
                    if (params.m_mainInputSource == PythonEnvironmentProviderNodeParameters.MainInputSource.TOML_FILE) {
                        // File-based: get parent directory of selected TOML file
                        if (params.m_tomlFile == null || params.m_tomlFile.m_path == null) {
                            throw new IllegalStateException("No TOML file selected.");
                        }
                        final Path tomlPath = Path.of(params.m_tomlFile.m_path.getPath());
                        if (!Files.exists(tomlPath)) {
                            throw new IllegalStateException("TOML file does not exist: " + tomlPath);
                        }
                        projectDir = tomlPath.getParent();
                        if (projectDir == null) {
                            throw new IllegalStateException("Cannot determine parent directory of TOML file: " + tomlPath);
                        }
                    } else {
                        // Bundled environment: get directory from bundling root
                        if (params.m_bundledEnvironment == null || params.m_bundledEnvironment.isEmpty()) {
                            throw new IllegalStateException("No bundled environment selected.");
                        }
                        try {
                            projectDir = PythonEnvironmentProviderNodeParameters.getBundlingRootPath()
                                .resolve(params.m_bundledEnvironment).toAbsolutePath().normalize();
                            final Path bundledTomlPath = projectDir.resolve("pixi.toml");
                            if (!Files.exists(bundledTomlPath)) {
                                throw new IllegalStateException("pixi.toml not found in bundled environment: " + bundledTomlPath);
                            }
                            LOGGER.debug("Using bundled environment at: " + projectDir);
                        } catch (Exception e) {
                            throw new IllegalStateException("Failed to access bundled environment: " + e.getMessage(), e);
                        }
                    }
                    
                    // Get lock content from params (may be from disk or generated)
                    String lockContent = params.getPixiLockFileContent();
                    
                    // If no lock in params, try reading from disk
                    if (lockContent == null || lockContent.isBlank()) {
                        final Path lockPath = projectDir.resolve("pixi.lock");
                        if (Files.exists(lockPath)) {
                            lockContent = Files.readString(lockPath);
                            LOGGER.debug("Read existing lock file from disk (" + lockContent.length() + " bytes)");
                        } else {
                            lockContent = "";
                            LOGGER.debug("No lock file found at " + lockPath);
                        }
                    }
                    
                    // Create port object with project directory - same for both file and bundled cases
                    portObject = new PythonEnvironmentPortObject(pixiTomlContent, lockContent, projectDir);
                    break;

                default:
                    throw new IllegalStateException("Unknown input source: " + params.m_mainInputSource);
            }

            out.setOutData(portObject);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read environment configuration: " + e.getMessage(), e);
        }
    }

}
