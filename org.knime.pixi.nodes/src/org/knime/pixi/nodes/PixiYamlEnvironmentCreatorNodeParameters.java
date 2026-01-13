package org.knime.pixi.nodes;

import org.knime.core.webui.node.dialog.defaultdialog.internal.button.ButtonWidget;
import org.knime.core.webui.node.dialog.defaultdialog.internal.button.CancelableActionHandler;
import org.knime.node.parameters.NodeParameters;
import org.knime.node.parameters.Widget;
import org.knime.node.parameters.updates.ParameterReference;
import org.knime.node.parameters.updates.ValueProvider;
import org.knime.node.parameters.updates.ValueReference;
import org.knime.node.parameters.widget.message.TextMessage;
import org.knime.node.parameters.widget.text.TextAreaWidget;

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

    // Button that triggers lock file generation and stores the result
    @Widget(title = "Check compatibility",
        description = "Click to check whether this environment can be constructed on all selected operating systems")
    @ButtonWidget(actionHandler = PixiLockActionHandler.class, updateHandler = PixiLockUpdateHandler.class)
    @ValueReference(ButtonFieldRef.class)
    String m_compatibilityCheckButton;

    // Hidden field that copies lock file content from button for validation message
    @ValueReference(PixiLockFileRef.class)
    @ValueProvider(LockFileCopyProvider.class)
    String m_pixiLockFileContent;

    @TextMessage(LockFileValidationProvider.class)
    Void m_lockFileValidationMessage;

    interface YamlContentRef extends ParameterReference<String> {
    }

    interface ButtonFieldRef extends ParameterReference<String> {
    }

    interface PixiLockFileRef extends ParameterReference<String> {
    }

    static final class PixiLockActionHandler
        extends PixiParameterUtils.AbstractPixiLockActionHandler<YamlContentGetter> {

        PixiLockActionHandler() {
            super("[PixiYaml]");
        }

        @Override
        protected String getManifestContent(final YamlContentGetter contentGetter) {
            return contentGetter.m_envYamlContent;
        }

        @Override
        protected String prepareManifestContent(final String content) throws Exception {
            return PixiYamlImporter.convertYamlToToml(content);
        }
    }

    static final class PixiLockUpdateHandler
        extends CancelableActionHandler.UpdateHandler<String, YamlContentGetter> {
    }

    static final class YamlContentGetter {
        @ValueReference(YamlContentRef.class)
        String m_envYamlContent;
    }

    static final class LockFileCopyProvider
        extends PixiParameterUtils.AbstractLockFileCopyProvider<ButtonFieldRef> {

        @Override
        protected Class<ButtonFieldRef> getButtonFieldRefClass() {
            return ButtonFieldRef.class;
        }
    }

    static final class LockFileValidationProvider
        extends PixiParameterUtils.AbstractLockFileValidationProvider<YamlContentRef, PixiLockFileRef, LockFileCopyProvider> {

        @Override
        protected Class<YamlContentRef> getContentRefClass() {
            return YamlContentRef.class;
        }

        @Override
        protected Class<PixiLockFileRef> getLockFileRefClass() {
            return PixiLockFileRef.class;
        }

        @Override
        protected Class<LockFileCopyProvider> getLockFileCopyProviderClass() {
            return LockFileCopyProvider.class;
        }
    }
}
