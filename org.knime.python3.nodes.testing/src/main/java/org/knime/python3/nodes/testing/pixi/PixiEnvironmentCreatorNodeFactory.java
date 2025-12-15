package org.knime.python3.nodes.testing.pixi;

import static org.knime.node.testing.DefaultNodeTestUtil.createNodeFactoryFromStage;

import org.knime.core.node.NodeFactory;
import org.knime.node.DefaultNode;
import org.knime.node.DefaultNodeFactory;
import org.knime.node.NodeType;
import org.knime.node.RequirePorts;
import org.knime.node.parameters.NodeParameters;
import org.knime.node.DefaultModel.ConfigureInput;
import org.knime.node.DefaultModel.ConfigureOutput;
import org.knime.node.DefaultModel.ExecuteInput;
import org.knime.node.DefaultModel.ExecuteOutput;
import org.knime.core.webui.node.dialog.defaultdialog.NodeParametersUtil;

/**
 * Factory for the "Pixi Environment Creator (Labs)" node.
 *
 * This node:
 *  - Has no data ports (source-like, produces only flow variables & a view)
 *  - Builds a pixi manifest from base environment + additional packages
 *  - Creates/reuses a cached environment (via PixiEnvironmentCreatorUtils)
 *  - Publishes flow variables:
 *      pixi_python_executable, pixi_env_dir, pixi_manifest_hash,
 *      pixi_platforms, pixi_base_environment, pixi_added_packages
 *
 * View: Provided via full description / potential future view settings (not implemented as a separate view settings
 * class here; manifest HTML could be surfaced later if required).
 *
 * NOTE: To register the node, add the usual extension point entry referencing this factory.
 */
public final class PixiEnvironmentCreatorNodeFactory extends DefaultNodeFactory {

    public PixiEnvironmentCreatorNodeFactory() {
        super(buildNodeDefinition());
    }

    private static DefaultNode buildNodeDefinition() {
        return DefaultNode.create()
            .name("Pixi Environment Creator (Labs)")
            .icon("icon.png")
            .shortDescription("Create or reuse a pixi environment from a KNIME base metapackage plus additional packages.")
            .fullDescription(
                """
                Assembles a pixi.toml manifest from a selected base KNIME Python environment plus optional additional packages (conda or pip).
                Resolves & installs the environment with caching via a stable manifest hash. Propagates the Python executable and environment metadata as flow variables.
                """
            )
            .nodeType(NodeType.Source)
            .sinceVersion("5.6") // Adjust to actual introduction version
            .keywords("pixi", "python", "environment", "conda", "pip")
            .ports(p -> p /* no data ports */)
            .model(modelStage -> modelStage
                .parameters(PixiEnvironmentCreatorNodeParameters.class)
                .configure(PixiEnvironmentCreatorNodeFactory::configureModel)
                .execute(PixiEnvironmentCreatorNodeFactory::executeModel));
    }

    private static void configureModel(final ConfigureInput in, final ConfigureOutput out) {
        final PixiEnvironmentCreatorNodeParameters params =
            NodeParametersUtil.getParameters(in.getParameters(), PixiEnvironmentCreatorNodeParameters.class);

        // Autodetect pixi executable if empty.
        if (params.m_pixiExecutable == null || params.m_pixiExecutable.isBlank()) {
            params.m_resolvedPixiExecutable = PixiEnvironmentCreatorUtils.locatePixiExecutable().orElse("");
        } else {
            params.m_resolvedPixiExecutable = params.m_pixiExecutable;
        }

        if (params.m_resolvedPixiExecutable == null || params.m_resolvedPixiExecutable.isBlank()) {
            throw new IllegalArgumentException("No pixi executable found, the advanced setting must be configured manually.");
        }
        if (!PixiEnvironmentCreatorUtils.validateExecutable(params.m_resolvedPixiExecutable)) {
            throw new IllegalArgumentException("No pixi executable found at configured location: " + params.m_resolvedPixiExecutable);
        }

        // Build preview manifest quickly (no locking/caching here).
        params.m_manifestPreview = PixiEnvironmentCreatorUtils.buildManifestText(params);

        // Source node with no data outputs -> no specs to set.
        out.setOutSpecs(); // Empty
    }

    private static void executeModel(final ExecuteInput in, final ExecuteOutput out) {
        final PixiEnvironmentCreatorNodeParameters params =
            NodeParametersUtil.getParameters(in.getParameters(), PixiEnvironmentCreatorNodeParameters.class);

        final var execCtx = in.getExecutionContext();
        execCtx.setProgress(0.0, "Preparing environment specification");

        final String manifestText = PixiEnvironmentCreatorUtils.buildManifestText(params);

        // Cache / create environment.
        final var cacheResult = PixiEnvironmentCreatorUtils.createOrGetCachedEnvironment(
            manifestText,
            params.m_resolvedPixiExecutable,
            "manifest",
            "creator",
            execCtx);

        final var envDir = cacheResult.environmentDir();
        final var manifestHash = cacheResult.manifestHash();

        final var pythonExec = PixiEnvironmentCreatorUtils.derivePythonExecutable(envDir);
        if (!PixiEnvironmentCreatorUtils.fileExists(pythonExec)) {
            throw new IllegalStateException("Python executable not found in pixi environment: " + pythonExec);
        }

        // Flow variables mirroring Python implementation.
        execCtx.setFlowVariableString("pixi_python_executable", pythonExec);
        execCtx.setFlowVariableString("pixi_env_dir", envDir);
        execCtx.setFlowVariableString("pixi_manifest_hash", manifestHash);
        execCtx.setFlowVariableString("pixi_platforms", "win-64,linux-64,osx-64,osx-arm64");
        execCtx.setFlowVariableString("pixi_base_environment", params.m_baseEnvironment.getValue().name());

        // Join added package names (non-empty only).
        final String addedPackages = PixiEnvironmentCreatorUtils.joinAddedPackageNames(params);
        execCtx.setFlowVariableString("pixi_added_packages", addedPackages);

        // Optionally, expose manifest preview again (not a flow variable here).
        params.m_manifestPreview = manifestText;

        execCtx.setProgress(1.0, "Environment ready");
        // No data/table outputs; a future dedicated view could render manifestText as HTML (not implemented here).
    }
}