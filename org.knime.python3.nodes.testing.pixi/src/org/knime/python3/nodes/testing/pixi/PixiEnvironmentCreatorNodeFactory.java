package org.knime.python3.nodes.testing.pixi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
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
                        // TODO Auto-generated catch block
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
        throws IOException, PixiBinaryLocationException, CanceledExecutionException, InterruptedException {
        final PixiEnvironmentCreatorNodeParameters params = in.getParameters();
        final String manifestText = params.m_pixiTomlContent;

        if (manifestText == null || manifestText.isBlank()) {
            throw new IllegalStateException("pixi.toml manifest text is empty.");
        }

        final String envName = "default";
        final Path projectDir = resolveProjectDir(manifestText);
        Files.createDirectories(projectDir);

        final Path manifestPath = projectDir.resolve("pixi.toml");
        Files.writeString(manifestPath, manifestText, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);

        final var execCtx = in.getExecutionContext();

        execCtx.setProgress(0.1, "Running pixi install");

        final Path pixiHome = projectDir.resolve(".pixi-home");
        Files.createDirectories(pixiHome);

        final Map<String, String> extraEnv = Map.of("PIXI_HOME", pixiHome.toString());

        final String[] pixiArgs = {"--color", "never", "--no-progress", "install", "--environment", envName};

        final CallResult callResult = PixiBinary.callPixi(projectDir, extraEnv, pixiArgs);

        if (callResult.returnCode() != 0) {
            // Build a helpful error message from whatever CallResult exposes.
            // (Adjust getters if your CallResult uses different names.)
            final String stdout = callResult.stdout() == null ? "" : callResult.stdout();
            final String stderr = callResult.stderr() == null ? "" : callResult.stderr();

            final String msg = "pixi install failed (exit code " + callResult.returnCode() + ").\n"
                + (stderr.isBlank() ? "" : "---- stderr ----\n" + stderr + "\n")
                + (stdout.isBlank() ? "" : "---- stdout ----\n" + stdout + "\n");

            throw new IllegalStateException(msg);
        }

        execCtx.setProgress(1.0, "Environment ready");
        // No data/table outputs; a future dedicated view could render manifestText as HTML (not implemented here).
    }

    private static Path resolveProjectDir(final String manifestText) {
        // TODO: this probably should not be hard coded
        final Path base = Paths.get(System.getProperty("user.home"), ".knime", "pixi-env-cache");
        final String hash = sha256Hex(manifestText);
        return base.resolve(hash);
    }

    private static String sha256Hex(final String s) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute SHA-256 hash.", e);
        }
    }
}
