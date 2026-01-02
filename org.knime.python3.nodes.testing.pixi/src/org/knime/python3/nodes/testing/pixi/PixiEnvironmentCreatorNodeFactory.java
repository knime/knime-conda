package org.knime.python3.nodes.testing.pixi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.knime.conda.envinstall.pixi.PixiBinary;
import org.knime.conda.envinstall.pixi.PixiBinary.CallResult;
import org.knime.conda.envinstall.pixi.PixiBinary.PixiBinaryLocationException;
import org.knime.core.node.KNIMEException;
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

@SuppressWarnings("restriction")
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
                .configure(PixiEnvironmentCreatorNodeFactory::configure) //
                .execute(PixiEnvironmentCreatorNodeFactory::execute)) //
            .keywords("pixi", "python", "environment", "conda", "pip"); //
    }

    private static void configure(final ConfigureInput in, final ConfigureOutput out) {
        final PixiEnvironmentCreatorNodeParameters params = in.getParameters();

        // Source node with no data outputs -> no specs to set.
        out.setOutSpecs(); // Empty
    }

    private static void execute(final ExecuteInput in, final ExecuteOutput out) {
        final PixiEnvironmentCreatorNodeParameters params = in.getParameters();
        final String manifestText = params.m_pixiTomlContent;

        final var execCtx = in.getExecutionContext();
        execCtx.setProgress(0.1, "Running pixi install");

        Path pixiHome, projectDir;
        String envName;

        // Setup
        try {
            projectDir = PixiUtils.saveManifestToDisk(manifestText);
            envName = "default";
            pixiHome = projectDir.resolve(".pixi-home");
            Files.createDirectories(pixiHome);
        } catch (IOException e) {
            execCtx.setMessage("Could not save pixi manifest to disk");
            throw new KNIMEException("Could not save pixi manifest to disk", e).toUnchecked();
        }

        try {
            final Map<String, String> extraEnv = Map.of("PIXI_HOME", pixiHome.toString());
            final String[] pixiArgs = {"--color", "never", "--no-progress", "install", "--environment", envName};
            final CallResult callResult = PixiBinary.callPixi(projectDir, extraEnv, pixiArgs);

            if (callResult.returnCode() != 0) {
                String errorDetails = PixiUtils.getMessageFromCallResult(callResult);
                execCtx.setMessage("Error: " + errorDetails);
            }
        } catch (PixiBinaryLocationException e) {
            execCtx.setMessage("Could not find pixi executable");
            throw new KNIMEException("Could not find pixi executable", e).toUnchecked();
        } catch (IOException e) {
            execCtx.setMessage("Could not run pixi executable");
            throw new KNIMEException("Could not run pixi executable", e).toUnchecked();
        } catch (InterruptedException e) {
            execCtx.setMessage("Pixi call got interrupted");
            Thread.currentThread().interrupt();
            throw new KNIMEException("Pixi call got interrupted", e).toUnchecked();
        }

        execCtx.setProgress(1.0, "Environment ready");

        final Path pythonEnvPath = PixiUtils.resolvePython(projectDir, envName);

        // TODO: finish this, set pythonEnvPath as output of the node
        FlowVariablePortObject outputObject = FlowVariablePortObject.INSTANCE;

        out.setOutData(outputObject);
    }
}
