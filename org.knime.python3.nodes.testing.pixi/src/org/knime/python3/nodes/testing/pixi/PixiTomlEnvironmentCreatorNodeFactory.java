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
            .fullDescription(
                """
                        Assembles a pixi.toml manifest from a selected base KNIME Python environment plus optional additional packages (conda or pip).
                        Resolves and installs the environment with caching via a stable manifest hash. Propagates the Python executable and environment metadata as flow variables.
                        """) //
            .sinceVersion(5, 10, 0) //
            .ports(p -> {
                p.addOutputPort("Pixi Environment", "Pixi Python environment information",
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
