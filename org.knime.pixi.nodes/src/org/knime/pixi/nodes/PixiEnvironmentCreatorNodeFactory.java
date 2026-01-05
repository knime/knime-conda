package org.knime.pixi.nodes;

import java.nio.file.Path;

import org.knime.node.DefaultModel.ConfigureInput;
import org.knime.node.DefaultModel.ConfigureOutput;
import org.knime.node.DefaultModel.ExecuteInput;
import org.knime.node.DefaultModel.ExecuteOutput;
import org.knime.node.DefaultNode;
import org.knime.node.DefaultNodeFactory;

/**
 * NodeFactory for the Pixi Environment Creator node where the user can add packages manually
 *
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 * @since 5.10
 */
@SuppressWarnings("restriction")
public final class PixiEnvironmentCreatorNodeFactory extends DefaultNodeFactory {

    /**
     * Constructor
     */
    public PixiEnvironmentCreatorNodeFactory() {
        super(buildNodeDefinition());
    }

    private static DefaultNode buildNodeDefinition() {
        return DefaultNode.create().name("Pixi Environment Creator (Labs)") //
            .icon("icon.png") //
            .shortDescription(
                "Create a pixi environment by specifying packages") //
            .fullDescription( //
                """
                        Creates a Python or R environment using <a href="https://pixi.sh">Pixi</a>, a fast package manager
                        that provides reproducible environments across platforms.

                        This node offers a user-friendly interface for building pixi environments by adding packages one by
                        one. Simply specify the package name, choose between conda or pip as the source, and optionally
                        define version constraints.

                        <h3>Features</h3>
                        <ul>
                        <li><b>Simple Package Management:</b> Add packages individually with an intuitive array-based UI,
                        no need to write TOML manually.</li>
                        <li><b>Flexible Versioning:</b> Specify minimum and/or maximum version constraints for each package,
                        or leave empty to get the latest version.</li>
                        <li><b>Mixed Package Sources:</b> Combine conda and pip packages in a single environment with proper
                        dependency resolution.</li>
                        <li><b>Multi-Platform Support:</b> Environments are automatically configured for all major operating
                        systems (win-64, linux-64, osx-64, osx-arm64) ensuring portability and KNIME Hub deployment.</li>
                        <li><b>Compatibility Check:</b> Use the "Check compatibility" button to verify that all packages can
                        be resolved on all configured platforms before execution.</li>
                        </ul>

                        <h3>Usage</h3>
                        <ol>
                        <li>Click "Add package" to add a new package to your environment</li>
                        <li>Enter the package name (e.g., "numpy", "pandas", "pytorch")</li>
                        <li>Select the source: Conda (default) or Pip</li>
                        <li>Optionally specify version constraints:
                            <ul>
                            <li>Min version: minimum version (inclusive)</li>
                            <li>Max version: maximum version (exclusive)</li>
                            </ul>
                        </li>
                        <li>Click "Check compatibility" to validate the environment before execution</li>
                        </ol>

                        The node comes with sensible defaults: <tt>python</tt> and <tt>knime-python-base</tt> from conda.

                        The created environment is output as a port object that can be connected to Python Script or other
                        nodes that consume Pixi environments.
                        """) //
            .sinceVersion(5, 10, 0) //
            .ports(p -> {
                p.addOutputPort("Pixi Environment", "Pixi Python environment information", PixiEnvironmentPortObject.TYPE);
            }).model(modelStage -> modelStage //
                .parametersClass(PixiEnvironmentCreatorNodeParameters.class) //
                .configure(PixiEnvironmentCreatorNodeFactory::configure) //
                .execute(PixiEnvironmentCreatorNodeFactory::execute)) //
            .keywords("pixi", "python", "environment", "conda", "pip", "packages"); //
    }

    private static void configure(final ConfigureInput in, final ConfigureOutput out) {
        // Set the spec for the output port
        out.setOutSpecs(PixiEnvironmentPortObjectSpec.INSTANCE);
    }

    private static void execute(final ExecuteInput in, final ExecuteOutput out) {
        final PixiEnvironmentCreatorNodeParameters params = in.getParameters();
        final String manifestText = params.buildPixiToml();

        final var execCtx = in.getExecutionContext();

        // Use shared executor
        final Path pythonEnvPath = PixiEnvironmentExecutor.executePixiInstall(manifestText, execCtx);

        // Create and output the Pixi environment port object containing the Python path
        final PixiEnvironmentPortObject portObject = new PixiEnvironmentPortObject(pythonEnvPath);
        out.setOutData(portObject);
    }
}
