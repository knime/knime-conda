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
 * NodeFactory for the Pixi TOML Environment Reader node
 *
 * @author Marc Lehner, KNIME GmbH, Konstanz, Germany
 * @since 5.10
 */
@SuppressWarnings("restriction")
public final class PixiTomlEnvironmentReaderNodeFactory extends DefaultNodeFactory {

    public PixiTomlEnvironmentReaderNodeFactory() {
        super(buildNodeDefinition());
    }

    private static DefaultNode buildNodeDefinition() {
        return DefaultNode.create().name("Pixi TOML Environment Reader (Labs)") //
            .icon("icon.png") //
            .shortDescription("Read a pixi environment from an existing pixi.toml file") //
            .fullDescription("""
                    Creates a Python or R environment using <a href="https://pixi.sh">Pixi</a>, a fast package manager
                    that provides reproducible environments across platforms, by reading an existing pixi.toml file.

                    This node allows you to specify a path to an existing pixi.toml manifest file on disk.
                    The pixi.toml file should be located in a valid pixi project directory structure.

                    <h3>Features</h3>
                    <ul>
                    <li><b>Use Existing Projects:</b> Point to an existing pixi project and use its environment
                    in KNIME workflows without duplicating the manifest content.</li>
                    <li><b>Reproducible Environments:</b> The same pixi.toml will create identical environments across
                    Windows, Linux, and macOS, making workflows portable and deployable.</li>
                    <li><b>Multi-Platform Support:</b> Works with pixi.toml files configured for all major operating systems
                    (win-64, linux-64, osx-64, osx-arm64) for seamless sharing and deployment to KNIME Hub.</li>
                    </ul>

                    <h3>Usage</h3>
                    Select a pixi.toml file from your file system. The file should be part of a valid pixi project structure.
                    The node will use the directory containing the file as the pixi project directory and install the
                    environment based on the manifest specification.

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
                .parametersClass(PixiTomlEnvironmentReaderNodeParameters.class) //
                .configure(PixiTomlEnvironmentReaderNodeFactory::configure) //
                .execute(PixiTomlEnvironmentReaderNodeFactory::execute)) //
            .keywords("pixi", "python", "environment", "conda", "pip", "toml", "file"); //
    }

    private static void configure(final ConfigureInput in, final ConfigureOutput out) {
        // Set the spec for the output port
        out.setOutSpecs(PixiEnvironmentPortObjectSpec.INSTANCE);
    }

    private static void execute(final ExecuteInput in, final ExecuteOutput out) {
        final PixiTomlEnvironmentReaderNodeParameters params = in.getParameters();
        final Path tomlFilePath = Path.of(params.m_tomlFile.m_path.getPath());

        final var execCtx = in.getExecutionContext();

        // Use shared executor - pass empty string for manifestText since we're using file path
        final Path environmentPath = PixiEnvironmentExecutor.executePixiInstall("", tomlFilePath, null, execCtx);

        // Create and output the Pixi environment port object
        final PixiEnvironmentPortObject portObject = new PixiEnvironmentPortObject(environmentPath);
        out.setOutData(portObject);
    }
}
