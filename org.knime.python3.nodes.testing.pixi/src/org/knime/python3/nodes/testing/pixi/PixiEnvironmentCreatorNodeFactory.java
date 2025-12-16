package org.knime.python3.nodes.testing.pixi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.knime.conda.envinstall.pixi.PixiBinary;
import org.knime.conda.envinstall.pixi.PixiBinary.CallResult;
import org.knime.conda.envinstall.pixi.PixiBinary.PixiBinaryLocationException;
import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.port.flowvariable.FlowVariablePortObject;
import org.knime.node.DefaultModel.ConfigureInput;
import org.knime.node.DefaultModel.ConfigureOutput;
import org.knime.node.DefaultModel.ExecuteInput;
import org.knime.node.DefaultModel.ExecuteOutput;
import org.knime.node.DefaultNode;
import org.knime.node.DefaultNodeFactory;

// Things that are left:
// 1 - in PixiUtils finish parsing CallResult, TODO is left there
// 2 - in this file line 95 set properly output, TODO left there

public final class PixiEnvironmentCreatorNodeFactory extends DefaultNodeFactory {

    public PixiEnvironmentCreatorNodeFactory() {
        super(buildNodeDefinition());
    }

    private static DefaultNode buildNodeDefinition() {
        return DefaultNode.create().name("Pixi Environment Creator (Labs)") //
            .icon("icon.png") //
            .shortDescription(
                "Create or reuse a pixi environment from a KNIME base metapackage plus additional packages.") //
            .fullDescription( //
                """
                        Assembles a pixi.toml manifest from a selected base KNIME Python environment plus optional additional packages (conda or pip).
                        Resolves and installs the environment with caching via a stable manifest hash. Propagates the Python executable and environment metadata as flow variables.
                        """) //
            .sinceVersion(5, 10, 0) //
            .ports(p -> {
                p.addOutputPort("Output", "Output Flow Variables", FlowVariablePortObject.TYPE);
            }).model(modelStage -> modelStage //
                .parametersClass(PixiEnvironmentCreatorNodeParameters.class) //
                .configure(PixiEnvironmentCreatorNodeFactory::configureModel) //
                .execute((input, output) -> {
                    try {
                        executeModel(input, output);
                    } catch (IOException | PixiBinaryLocationException | InterruptedException ex) {
                    }
                })) //
            .keywords("pixi", "python", "environment", "conda", "pip"); //
    }

    private static void configureModel(final ConfigureInput in, final ConfigureOutput out) {
        final PixiEnvironmentCreatorNodeParameters params = in.getParameters();

        // Source node with no data outputs -> no specs to set.
        out.setOutSpecs(); // Empty
    }

    private static void executeModel(final ExecuteInput in, final ExecuteOutput out)
        throws IOException, CanceledExecutionException, InterruptedException, PixiBinaryLocationException {
        final PixiEnvironmentCreatorNodeParameters params = in.getParameters();
        final String manifestText = params.m_pixiTomlContent;

        final Path projectDir = PixiUtils.saveManifestToDisk(manifestText);
        final String envName = "default";

        final var execCtx = in.getExecutionContext();

        execCtx.setProgress(0.1, "Running pixi install");

        final Path pixiHome = projectDir.resolve(".pixi-home");
        Files.createDirectories(pixiHome);

        final Map<String, String> extraEnv = Map.of("PIXI_HOME", pixiHome.toString());

        final String[] pixiArgs = {"--color", "never", "--no-progress", "install", "--environment", envName};

        final CallResult callResult = PixiBinary.callPixi(projectDir, extraEnv, pixiArgs);

        if (callResult.returnCode() != 0) {

            String errorDetails = PixiUtils.getMessageFromCallResult(callResult);

            execCtx.setMessage("Error: " + errorDetails);
        }

        execCtx.setProgress(1.0, "Environment ready");

        final Path pythonEnvPath = PixiUtils.resolvePython(projectDir, envName);

        // TODO: finish this, set pythonEnvPath as output of the node
        FlowVariablePortObject outputObject = FlowVariablePortObject.INSTANCE;
        out.setOutData(outputObject);
    }
}
