package org.knime.python3.nodes.testing.pixi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.knime.conda.envinstall.pixi.PixiBinary;
import org.knime.conda.envinstall.pixi.PixiBinary.CallResult;
import org.knime.conda.envinstall.pixi.PixiBinary.PixiBinaryLocationException;
import org.knime.core.webui.node.dialog.defaultdialog.internal.button.SimpleButtonWidget;
import org.knime.node.parameters.NodeParameters;
import org.knime.node.parameters.NodeParametersInput;
import org.knime.node.parameters.Widget;
import org.knime.node.parameters.layout.After;
import org.knime.node.parameters.layout.Layout;
import org.knime.node.parameters.layout.Section;
import org.knime.node.parameters.updates.ButtonReference;
import org.knime.node.parameters.updates.ParameterReference;
import org.knime.node.parameters.updates.StateProvider;
import org.knime.node.parameters.updates.ValueReference;
import org.knime.node.parameters.widget.message.TextMessage;
import org.knime.node.parameters.widget.message.TextMessage.MessageType;
import org.knime.node.parameters.widget.text.TextAreaWidget;

public final class PixiEnvironmentCreatorNodeParameters implements NodeParameters {

    @Section(title = "Environment Setup")
    interface SetupSection {

    }

    @Section(title = "Lock file generation")
    @After(SetupSection.class)
    interface LockFileGeneration {

    }

    static final class GeneratePixiLockButtonRef implements ButtonReference {
    }


    @Widget(title = "pixi.toml", description = "Content of your pixi toml")
    @Layout(SetupSection.class)
    @TextAreaWidget(rows = 20)
    @ValueReference(PixiTomlContentRef.class)
    String m_pixiTomlContent = "";

    @Widget(title = "Generate lock file", description = "Click to generate pixi lock file")
    @SimpleButtonWidget(ref = GeneratePixiLockButtonRef.class)
    @Layout(LockFileGeneration.class)
    Void m_executeButton;

    @TextMessage(PixiLockResultProvider.class)
    @Layout(LockFileGeneration.class)
    Void m_resultMessage;

    interface PixiTomlContentRef extends ParameterReference<String> {
    }

    static final class PixiLockResultProvider implements StateProvider<Optional<TextMessage.Message>> {

        private Supplier<String> m_tomlContentSupplier;

        @Override
        public void init(final StateProviderInitializer initializer) {
            initializer.computeOnButtonClick(GeneratePixiLockButtonRef.class);
            m_tomlContentSupplier = initializer.getValueSupplier(PixiTomlContentRef.class);
        }

        @Override
        public Optional<TextMessage.Message> computeState(final NodeParametersInput context) {
            String manifestText = m_tomlContentSupplier.get();
            try {
                final Path projectDir = PixiUtils.saveManifestToDisk(manifestText);
                final String envName = "default";
                final Path pixiHome = projectDir.resolve(".pixi-home");
                Files.createDirectories(pixiHome);

                final Map<String, String> extraEnv = Map.of("PIXI_HOME", pixiHome.toString());

                final String[] pixiArgs = {"--color", "never", "--no-progress", "lock", "--environment", envName};

                final CallResult callResult = PixiBinary.callPixi(projectDir, extraEnv, pixiArgs);

                if (callResult.returnCode() != 0) {
                    String errorDetails = PixiUtils.getMessageFromCallResult(callResult);
                    return Optional.of(new TextMessage.Message("Error", errorDetails, MessageType.ERROR));
                }

                return Optional
                    .of(new TextMessage.Message("Success", "pixi.lock generated." + manifestText, MessageType.INFO));

            } catch (IOException ex) {
                return Optional.of(
                    new TextMessage.Message("Error", "Unknown error occured" + ex.getMessage(), MessageType.ERROR));
            } catch (PixiBinaryLocationException ex) {
                return Optional.of(
                    new TextMessage.Message("Error", "Pixi binary is not found" + ex.getMessage(), MessageType.ERROR));
            } catch (InterruptedException ex) {
                return Optional.of(new TextMessage.Message("Error", "Operation was interrupted", MessageType.ERROR));
            }
            return null;
        }
    }
}
