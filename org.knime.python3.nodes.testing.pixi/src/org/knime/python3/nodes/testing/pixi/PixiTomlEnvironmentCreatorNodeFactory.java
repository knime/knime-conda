package org.knime.python3.nodes.testing.pixi;

import java.nio.file.Path;

import org.knime.node.DefaultModel.ConfigureInput;
import org.knime.node.DefaultModel.ConfigureOutput;
import org.knime.node.DefaultModel.ExecuteInput;
import org.knime.node.DefaultModel.ExecuteOutput;
import org.knime.node.DefaultNode;
import org.knime.node.DefaultNodeFactory;

/**
 * NodeFactory for the TOML-based Pixi Environment Creator node
 *
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 * @since 5.10
 */
@SuppressWarnings("restriction")
public final class PixiTomlEnvironmentCreatorNodeFactory extends DefaultNodeFactory {

    public PixiTomlEnvironmentCreatorNodeFactory() {
        super(buildNodeDefinition());
    }

    private static DefaultNode buildNodeDefinition() {
        return DefaultNode.create().name("Pixi TOML Environment Creator (Labs)") //
            .icon("icon.png") //
            .shortDescription("Create a pixi environment based on the provided TOML file content") //
            .fullDescription("""
                    Creates a Python or R environment using <a href="https://pixi.sh">Pixi</a>, a fast package manager
                    that provides reproducible environments across platforms.

                    This node allows you to define your environment by providing a complete pixi.toml manifest file.
                    The pixi.toml format gives you full control over the environment configuration, including workspace
                    settings, channels, platforms, and dependencies from both conda and pip ecosystems.

                    <h3>Features</h3>
                    <ul>
                    <li><b>Reproducible Environments:</b> The same pixi.toml will create identical environments across
                    Windows, Linux, and macOS, making workflows portable and deployable.</li>
                    <li><b>Multi-Platform Support:</b> Configure your environment to work on all major operating systems
                    (win-64, linux-64, osx-64, osx-arm64) for seamless sharing and deployment to KNIME Hub.</li>
                    <li><b>Mixed Package Sources:</b> Combine conda and pip packages in a single environment with proper
                    dependency resolution.</li>
                    <li><b>Compatibility Check:</b> Use the "Check compatibility" button to verify that the environment
                    can be created on all configured platforms before execution.</li>
                    </ul>

                    <h3>Usage</h3>
                    Paste or edit your pixi.toml content in the text area. The manifest should include:
                    <ul>
                    <li><tt>[workspace]</tt> section with channels and platforms</li>
                    <li><tt>[dependencies]</tt> section for conda packages</li>
                    <li><tt>[pypi-dependencies]</tt> section for pip packages (optional)</li>
                    </ul>

                    See the <a href="https://pixi.prefix.dev/latest/reference/pixi_manifest/">pixi manifest
                    specification</a> for complete documentation on the pixi.toml format.

                    The created environment is output as a port object that can be connected to Python Script or other
                    nodes that consume Python environments.
                    """) //
            .sinceVersion(5, 10, 0) //
            .ports(p -> {
                p.addOutputPort("Pixi Environment", "Pixi Python environment information to be used by script nodes",
                    PixiEnvironmentPortObject.TYPE);
            }).model(modelStage -> modelStage //
                .parametersClass(PixiTomlEnvironmentCreatorNodeParameters.class) //
                .configure(PixiTomlEnvironmentCreatorNodeFactory::configure) //
                .execute(PixiTomlEnvironmentCreatorNodeFactory::execute)) //
            .keywords("pixi", "python", "environment", "conda", "pip"); //
    }

    private static void configure(final ConfigureInput in, final ConfigureOutput out) {
        // Set the spec for the output port
        out.setOutSpecs(PixiEnvironmentPortObjectSpec.INSTANCE);
    }

    private static void execute(final ExecuteInput in, final ExecuteOutput out) {
        final PixiTomlEnvironmentCreatorNodeParameters params = in.getParameters();
        final String manifestText = params.m_pixiTomlContent;

        final var execCtx = in.getExecutionContext();

        // Use shared executor
        final Path pythonEnvPath = PixiEnvironmentExecutor.executePixiInstall(manifestText, execCtx);

        // Create and output the Pixi environment port object containing the Python path
        final PixiEnvironmentPortObject portObject = new PixiEnvironmentPortObject(pythonEnvPath);
        out.setOutData(portObject);
    }
}
