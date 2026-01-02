package org.knime.pixi.nodes;

import org.knime.core.webui.node.dialog.defaultdialog.internal.button.ButtonWidget;
import org.knime.core.webui.node.dialog.defaultdialog.internal.button.CancelableActionHandler;
import org.knime.node.parameters.NodeParameters;
import org.knime.node.parameters.Widget;
import org.knime.node.parameters.updates.ParameterReference;
import org.knime.node.parameters.updates.ValueReference;
import org.knime.node.parameters.widget.text.TextAreaWidget;
import org.knime.pixi.nodes.PixiUtils.AbstractPixiLockActionHandler;

/**
 * Node Parameters describing the dialog for the YAML-based Pixi Environment Creator node
 *
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 * @since 5.10
 */
@SuppressWarnings("restriction")
final class PixiYamlEnvironmentCreatorNodeParameters implements NodeParameters {
    @Widget(title = "Environment specification (conda environment.yaml)", description = """
            Content of the conda environment.yaml file that describes the environment.
            This will be imported into pixi using `pixi init --import` and converted to a pixi.toml manifest.
            The environment will automatically be configured to work on all major platforms (win-64, linux-64, osx-64, osx-arm64).
            """)
    @TextAreaWidget(rows = 20)
    @ValueReference(YamlContentRef.class)
    String m_envYamlContent = """
            name: myenv
            channels:
              - knime
              - conda-forge
            dependencies:
              - python=3.14.*
              - knime-python-base=5.9.*
            """;

    @Widget(title = "Check compatibility",
        description = "Click to check whether this environment can be constructed on all selected operating systems")
    @ButtonWidget(actionHandler = PixiLockActionHandler.class, updateHandler = PixiLockUpdateHandler.class)
    String m_compatibilityCheckButton;

    interface YamlContentRef extends ParameterReference<String> {
    }

    static final class PixiLockActionHandler extends AbstractPixiLockActionHandler<YamlContent> {
        @Override
        protected String getTomlContent(final YamlContent dependency) {
            return PixiYamlImporter.convertYamlToToml(dependency.m_envYamlContent);
        }
    }

    static final class PixiLockUpdateHandler extends CancelableActionHandler.UpdateHandler<String, YamlContent> {
    }

    static final class YamlContent {
        // Setting name must match the parameter name that we want to retrieve
        String m_envYamlContent;
    }
}
