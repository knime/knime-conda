package org.knime.pixi.nodes;

import java.nio.file.Path;

import org.knime.node.DefaultModel.ConfigureInput;
import org.knime.node.DefaultModel.ConfigureOutput;
import org.knime.node.DefaultModel.ExecuteInput;
import org.knime.node.DefaultModel.ExecuteOutput;
import org.knime.node.DefaultNode;
import org.knime.node.DefaultNodeFactory;
import org.knime.pixi.port.PixiEnvironmentPortObject;
import org.knime.pixi.port.PixiEnvironmentPortObjectSpec;

/**
 * NodeFactory for the YAML-based Pixi Environment Creator node
 *
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 * @since 5.10
 */
public final class PixiYamlEnvironmentCreatorNodeFactory extends DefaultNodeFactory {

    /**
     * Constructor
     */
    public PixiYamlEnvironmentCreatorNodeFactory() {
        super(buildNodeDefinition());
    }

    private static DefaultNode buildNodeDefinition() {
        return DefaultNode.create().name("Pixi YAML Environment Creator (Labs)") //
            .icon("icon.png") //
            .shortDescription("Create a pixi environment from a conda environment.yaml file") //
            .fullDescription("""
                    Creates a Python or R environment using <a href="https://pixi.sh">Pixi</a>, a fast package manager
                    that provides reproducible environments across platforms.

                    This node allows you to import existing conda environment.yaml files into pixi. The yaml file is
                    converted to pixi's native pixi.toml format using <tt>pixi init --import</tt>, and automatically
                    configured to work on all major platforms.

                    <h3>Features</h3>
                    <ul>
                    <li><b>Conda Compatibility:</b> Reuse existing conda environment.yaml files without modification.</li>
                    <li><b>Automatic Conversion:</b> The yaml is automatically converted to pixi.toml format using pixi's
                    built-in import functionality.</li>
                    <li><b>Multi-Platform Support:</b> Environments are automatically configured for all major operating
                    systems (win-64, linux-64, osx-64, osx-arm64) ensuring portability and KNIME Hub deployment.</li>
                    <li><b>Reproducible Environments:</b> The converted pixi.toml ensures identical environments across
                    platforms.</li>
                    <li><b>Compatibility Check:</b> Use the "Check compatibility" button to verify that the environment
                    can be created on all configured platforms before execution.</li>
                    </ul>

                    <h3>Usage</h3>
                    Paste your conda environment.yaml content in the text area. The file should follow the standard
                    conda environment.yaml format:
                    <pre>
name: myenv
channels:
  - conda-forge
dependencies:
  - python=3.13
  - numpy
  - pandas
  - pip:
    - some-pip-package
                    </pre>

                    See the <a href="https://conda.io/projects/conda/en/latest/user-guide/tasks/manage-environments.html#creating-an-environment-file-manually">conda
                    environment file documentation</a> for more details on the yaml format.

                    The created environment is output as a port object that can be connected to Python Script or other
                    nodes that consume Python environments.
                    """) //
            .sinceVersion(5, 10, 0) //
            .ports(p -> {
                p.addOutputPort("Pixi Environment", "Pixi Python environment information to be used by script nodes",
                    PixiEnvironmentPortObject.TYPE);
            }).model(modelStage -> modelStage //
                .parametersClass(PixiYamlEnvironmentCreatorNodeParameters.class) //
                .configure(PixiYamlEnvironmentCreatorNodeFactory::configure) //
                .execute(PixiYamlEnvironmentCreatorNodeFactory::execute)) //
            .keywords("pixi", "python", "environment", "conda", "yaml", "yml"); //
    }

    private static void configure(final ConfigureInput in, final ConfigureOutput out) {
        // Set the spec for the output port
        out.setOutSpecs(PixiEnvironmentPortObjectSpec.INSTANCE);
    }

    private static void execute(final ExecuteInput in, final ExecuteOutput out) {
        final PixiYamlEnvironmentCreatorNodeParameters params = in.getParameters();

        // Convert YAML to TOML with all platforms
        final String manifestText = PixiYamlImporter.convertYamlToToml(params.m_envYamlContent);

        final var execCtx = in.getExecutionContext();

        if (params.m_pixiLockFileContent != null && !params.m_pixiLockFileContent.isEmpty()) {
            System.out.println("[PixiYaml] Execute received lock file from settings (" 
                + params.m_pixiLockFileContent.length() + " chars)");
        } else {
            System.out.println("[PixiYaml] Execute: no lock file in settings");
        }

        // Use shared executor, passing lock file content from settings if available
        final Path environmentPath = PixiEnvironmentExecutor.executePixiInstall(
            manifestText, null, params.m_pixiLockFileContent, execCtx);

        // Create and output the Pixi environment port object
        final PixiEnvironmentPortObject portObject = new PixiEnvironmentPortObject(environmentPath);
        out.setOutData(portObject);
    }
}
