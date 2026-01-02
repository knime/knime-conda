package org.knime.python3.nodes.testing.pixi;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.knime.conda.envinstall.pixi.PixiBinary;
import org.knime.conda.envinstall.pixi.PixiBinary.CallResult;
import org.knime.conda.envinstall.pixi.PixiBinary.PixiBinaryLocationException;
import org.knime.core.webui.node.dialog.defaultdialog.internal.button.SimpleButtonWidget;
import org.knime.node.parameters.NodeParameters;
import org.knime.node.parameters.NodeParametersInput;
import org.knime.node.parameters.Widget;
import org.knime.node.parameters.updates.ButtonReference;
import org.knime.node.parameters.updates.ParameterReference;
import org.knime.node.parameters.updates.StateProvider;
import org.knime.node.parameters.updates.ValueReference;
import org.knime.node.parameters.widget.message.TextMessage;
import org.knime.node.parameters.widget.message.TextMessage.MessageType;
import org.knime.node.parameters.widget.text.TextAreaWidget;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;

@SuppressWarnings("restriction")
final class PixiEnvironmentCreatorNodeParameters implements NodeParameters {
    @Widget(title = "Environment specification (pixi.toml)", description = """
            Content of the pixi.toml that describes the environment that should be constructed by pixi.
            See [the pixi manifest specification](https://pixi.prefix.dev/latest/reference/pixi_manifest/) for details.
            It is recommended to list all platforms in the platforms section for optimal compatibility
            when sharing and deploying the workflow.
            """)
    @TextAreaWidget(rows = 20)
    @ValueReference(PixiTomlContentRef.class)
    String m_pixiTomlContent = """
            [workspace]
            channels = ["knime", "conda-forge"]
            platforms = ["win-64", "linux-64", "osx-64", "osx-arm64"]

            [dependencies]
            python = "3.13.*"
            knime-python-base = "5.9"
            """;

    @TextMessage(PlatformValidationProvider.class)
    Void m_platformValidationMessage;

    @Widget(title = "Check compatibility",
        description = "Click to check whether this pixi environment can be constructed on all selected operating systems")
    @SimpleButtonWidget(ref = GeneratePixiLockButtonRef.class)
    Void m_compatibilityCheckButton;

    @TextMessage(PixiLockResultProvider.class)
    Void m_compatibilityCheckResultMessage;

    interface PixiTomlContentRef extends ParameterReference<String> {
    }

    static final class GeneratePixiLockButtonRef implements ButtonReference {
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
                final Path pixiHome = projectDir.resolve(".pixi-home");
                Files.createDirectories(pixiHome);

                final Map<String, String> extraEnv = Map.of("PIXI_HOME", pixiHome.toString());

                final String[] pixiArgs = {"--color", "never", "--no-progress", "lock"};

                final CallResult callResult = PixiBinary.callPixi(projectDir, extraEnv, pixiArgs);

                if (callResult.returnCode() != 0) {
                    String errorDetails = PixiUtils.getMessageFromCallResult(callResult);
                    return Optional.of(new TextMessage.Message("Error", errorDetails, MessageType.ERROR));
                }

                return Optional
                    .of(new TextMessage.Message("Success",
                        "Conda environment is compatible with all selected operating systems.", MessageType.INFO));

            } catch (IOException ex) {
                return Optional
                    .of(new TextMessage.Message("Error", "Unknown error occured. " + ex.getMessage(),
                        MessageType.ERROR));
            } catch (PixiBinaryLocationException ex) {
                return Optional.of(
                    new TextMessage.Message("Error", "Pixi binary is not found. " + ex.getMessage(),
                        MessageType.ERROR));
            } catch (InterruptedException ex) {
                return Optional.of(new TextMessage.Message("Error", "Operation was interrupted.", MessageType.ERROR));
            }
        }
    }

    static final class PlatformValidationProvider implements StateProvider<Optional<TextMessage.Message>> {

        private static final Set<String> ALL_PLATFORMS =
            Set.of("win-64", "linux-64", "osx-64", "osx-arm64");

        private Supplier<String> m_tomlContentSupplier;

        @Override
        public void init(final StateProviderInitializer initializer) {
            initializer.computeOnValueChange(PixiTomlContentRef.class);
            initializer.computeAfterOpenDialog();
            m_tomlContentSupplier = initializer.getValueSupplier(PixiTomlContentRef.class);
        }

        @Override
        public Optional<TextMessage.Message> computeState(final NodeParametersInput context) {
            String tomlContent = m_tomlContentSupplier.get();

            if (tomlContent == null || tomlContent.isBlank()) {
                return Optional.empty();
            }

            try {
                TomlParser parser = new TomlParser();
                Config config = parser.parse(new StringReader(tomlContent));

                // Try to get the workspace section
                Config workspace = config.get("workspace");
                if (workspace == null) {
                    return Optional.of(new TextMessage.Message("Platform configuration",
                        "No '[workspace]' section found. Environment will only be checked for the current platform.",
                        MessageType.WARNING));
                }

                // Try to get the platforms list
                List<?> platformsList = workspace.get("platforms");
                if (platformsList == null || platformsList.isEmpty()) {
                    return Optional.of(new TextMessage.Message("Platform configuration",
                        "No 'platforms' field found in workspace section. Environment will only be checked for the current platform.",
                        MessageType.WARNING));
                }

                // Convert to set of strings
                Set<String> platforms = platformsList.stream()
                    .map(Object::toString)
                    .collect(Collectors.toSet());

                // Check if all platforms are present
                if (platforms.equals(ALL_PLATFORMS)) {
                    return Optional.of(new TextMessage.Message("Platform configuration",
                        "Pixi TOML is prepared for all operating systems.",
                        MessageType.INFO));
                }

                // Check whether all platforms are known
                Set<String> unknownPlatforms = new HashSet<>(platforms);
                unknownPlatforms.removeAll(ALL_PLATFORMS);

                if (!unknownPlatforms.isEmpty()) {
                    String unknownOSes = unknownPlatforms.stream().collect(Collectors.joining(", "));
                    String knownOSes = ALL_PLATFORMS.stream().collect(Collectors.joining(", "));

                    return Optional.of(new TextMessage.Message("Platform configuration",
                        "Unknown platform(s): " + unknownOSes + ". Platforms should be one of: " + knownOSes + ".",
                        MessageType.WARNING));
                }

                // Check which platforms are missing
                Set<String> missingPlatforms = new HashSet<>(ALL_PLATFORMS);
                missingPlatforms.removeAll(platforms);

                if (!missingPlatforms.isEmpty()) {
                    String missingOs = missingPlatforms.stream()
                        .map(PlatformValidationProvider::platformDisplayName)
                        .collect(Collectors.joining(", "));

                    return Optional.of(new TextMessage.Message("Platform configuration",
                        "Missing platform(s): " + missingOs + ". Environment may not work on all operating systems.",
                        MessageType.WARNING));
                }

                return Optional.empty();

            } catch (Exception ex) {
                return Optional.of(new TextMessage.Message("TOML Parse Error",
                    "Could not parse TOML content: " + ex.getMessage(),
                    MessageType.ERROR));
            }
        }

        private static String platformDisplayName(final String platformIdentifier) {
            if (platformIdentifier.startsWith("win-")) {
                return "Windows";
            }
            if (platformIdentifier.startsWith("linux-")) {
                return "Linux (and KNIME Hub)";
            }
            if (platformIdentifier.equals("osx-64")) {
                return "macOS (Intel)";
            }
            if (platformIdentifier.equals("osx-arm64")) {
                return "macOS (Apple Silicon)";
            }
            return platformIdentifier;
        }
    }
}
