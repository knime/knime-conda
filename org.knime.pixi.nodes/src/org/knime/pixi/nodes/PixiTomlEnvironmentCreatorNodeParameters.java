package org.knime.pixi.nodes;

import java.io.StringReader;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.knime.core.webui.node.dialog.defaultdialog.internal.button.ButtonWidget;
import org.knime.core.webui.node.dialog.defaultdialog.internal.button.CancelableActionHandler;
import org.knime.node.parameters.NodeParameters;
import org.knime.node.parameters.NodeParametersInput;
import org.knime.node.parameters.Widget;
import org.knime.node.parameters.updates.ParameterReference;
import org.knime.node.parameters.updates.StateProvider;
import org.knime.node.parameters.updates.ValueProvider;
import org.knime.node.parameters.updates.ValueReference;
import org.knime.node.parameters.widget.message.TextMessage;
import org.knime.node.parameters.widget.message.TextMessage.MessageType;
import org.knime.node.parameters.widget.text.TextAreaWidget;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;

/**
 * Node Parameters describing the dialog for the TOML-based Pixi Environment Creator node
 *
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 * @since 5.10
 */
@SuppressWarnings("restriction")
final class PixiTomlEnvironmentCreatorNodeParameters implements NodeParameters {
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
            knime-python-base = "5.9.*"
            """;

    // Button that triggers lock file generation and stores the result
    @Widget(title = "Check compatibility",
        description = "Click to check whether this pixi environment can be constructed on all selected operating systems")
    @ButtonWidget(actionHandler = PixiLockActionHandler.class, updateHandler = PixiLockUpdateHandler.class)
    @ValueReference(ButtonFieldRef.class)
    String m_compatibilityCheckButton;

    // Hidden field that copies lock file content from button for validation message
    @ValueReference(PixiLockFileRef.class)
    @ValueProvider(LockFileCopyProvider.class)
    String m_pixiLockFileContent;

    @TextMessage(PlatformValidationProvider.class)
    Void m_platformValidationMessage;

    @TextMessage(LockFileValidationProvider.class)
    Void m_lockFileValidationMessage;

    interface PixiTomlContentRef extends ParameterReference<String> {
    }

    interface ButtonFieldRef extends ParameterReference<String> {
    }

    interface PixiLockFileRef extends ParameterReference<String> {
    }

    static final class PixiLockActionHandler
        extends PixiParameterUtils.AbstractPixiLockActionHandler<TomlContentGetter> {

        PixiLockActionHandler() {
            super("[PixiToml]");
        }

        @Override
        protected String getManifestContent(final TomlContentGetter contentGetter) {
            return contentGetter.m_pixiTomlContent;
        }

        @Override
        protected String prepareManifestContent(final String content) {
            return content; // TOML is already in the right format
        }
    }

    static final class PixiLockUpdateHandler
        extends CancelableActionHandler.UpdateHandler<String, TomlContentGetter> {
    }

    static final class TomlContentGetter {
        @ValueReference(PixiTomlContentRef.class)
        String m_pixiTomlContent;
    }

    static final class LockFileCopyProvider
        extends PixiParameterUtils.AbstractLockFileCopyProvider<ButtonFieldRef> {

        @Override
        protected Class<ButtonFieldRef> getButtonFieldRefClass() {
            return ButtonFieldRef.class;
        }
    }

    static final class LockFileValidationProvider
        extends PixiParameterUtils.AbstractLockFileValidationProvider<PixiTomlContentRef, PixiLockFileRef, LockFileCopyProvider> {

        @Override
        protected Class<PixiTomlContentRef> getContentRefClass() {
            return PixiTomlContentRef.class;
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
