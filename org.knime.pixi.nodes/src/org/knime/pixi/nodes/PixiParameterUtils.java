package org.knime.pixi.nodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.knime.conda.envinstall.pixi.PixiBinary;
import org.knime.core.webui.node.dialog.defaultdialog.internal.button.CancelableActionHandler;
import org.knime.core.webui.node.dialog.defaultdialog.widget.handler.WidgetHandlerException;
import org.knime.node.parameters.NodeParametersInput;
import org.knime.node.parameters.updates.ParameterReference;
import org.knime.node.parameters.updates.StateProvider;
import org.knime.node.parameters.widget.message.TextMessage;
import org.knime.node.parameters.widget.message.TextMessage.MessageType;

/**
 * Utility class for reducing code duplication in Pixi parameter classes.
 *
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 * @since 5.10
 */
@SuppressWarnings("restriction")
final class PixiParameterUtils {

    private PixiParameterUtils() {
        // Utility class
    }

    /**
     * Base class for button action handlers that generate pixi lock files.
     * Handles the lock file generation logic with cancellation support.
     *
     * @param <ContentGetter> a functional interface that extracts the manifest content from dependencies
     */
    static abstract class AbstractPixiLockActionHandler<ContentGetter>
        extends CancelableActionHandler<String, ContentGetter> {

        private final String m_logPrefix;

        /**
         * Constructor.
         *
         * @param logPrefix prefix for console logging (e.g., "[PixiToml]" or "[PixiYaml]")
         */
        protected AbstractPixiLockActionHandler(final String logPrefix) {
            m_logPrefix = logPrefix;
        }

        /**
         * Extract the manifest content from the dependency object.
         */
        protected abstract String getManifestContent(ContentGetter contentGetter);

        /**
         * Prepare the manifest content for pixi lock. For TOML, this is a no-op. For YAML/packages, convert to TOML.
         *
         * @param content the raw content
         * @return the TOML manifest content ready for pixi
         * @throws Exception if conversion/preparation fails
         */
        protected abstract String prepareManifestContent(String content) throws Exception;

        @Override
        protected String invoke(final ContentGetter settings, final NodeParametersInput context)
            throws WidgetHandlerException {
            System.out.println(m_logPrefix + " Button clicked - running pixi lock...");

            try {
                final String rawContent = getManifestContent(settings);
                if (rawContent == null || rawContent.isBlank()) {
                    throw new WidgetHandlerException("No manifest content provided");
                }

                final String manifestContent = prepareManifestContent(rawContent);
                final Path projectDir = PixiUtils.resolveProjectDirectory(manifestContent, null);
                final Path pixiHome = projectDir.resolve(".pixi-home");
                Files.createDirectories(pixiHome);

                final Map<String, String> extraEnv = Map.of("PIXI_HOME", pixiHome.toString());
                final String[] pixiArgs = {"--color", "never", "--no-progress", "lock"};

                final var callResult = PixiBinary.callPixi(projectDir, extraEnv, pixiArgs);

                if (callResult.returnCode() != 0) {
                    String errorDetails = PixiUtils.getMessageFromCallResult(callResult);
                    System.out.println(m_logPrefix + " Pixi lock failed");
                    throw new WidgetHandlerException("Pixi lock failed:\n" + errorDetails);
                }

                // Read the generated lock file
                final Path lockFilePath = projectDir.resolve("pixi.lock");
                if (Files.exists(lockFilePath)) {
                    final String lockContent = Files.readString(lockFilePath);
                    System.out.println(m_logPrefix + " Lock file generated (" + lockContent.length() + " bytes)");
                    return lockContent;
                } else {
                    throw new WidgetHandlerException("Lock file was not generated at: " + lockFilePath);
                }
            } catch (WidgetHandlerException e) {
                throw e;
            } catch (Exception ex) {
                System.out.println(m_logPrefix + " Failed to generate lock file: " + ex.getMessage());
                throw new WidgetHandlerException("Failed to generate lock file: " + ex.getMessage());
            }
        }

        @Override
        protected String getButtonText(final States state) {
            return switch (state) {
                case READY -> "Check compatibility";
                case CANCEL -> "Cancel";
                case DONE -> "Recheck compatibility";
            };
        }

        @Override
        protected boolean isMultiUse() {
            return true; // Allow re-checking
        }
    }

    /**
     * State provider that copies the lock file content from the button field to a hidden field.
     * This enables the validation message to react to lock file changes.
     *
     * @param <ButtonFieldRef> the button field reference
     */
    static abstract class AbstractLockFileCopyProvider<ButtonFieldRef extends ParameterReference<String>>
        implements StateProvider<String> {

        private Supplier<String> m_buttonFieldSupplier;

        /**
         * Get the button field reference class.
         */
        protected abstract Class<ButtonFieldRef> getButtonFieldRefClass();

        @Override
        public void init(final StateProviderInitializer initializer) {
            // Trigger whenever button field changes (after lock file generation)
            initializer.computeOnValueChange(getButtonFieldRefClass());
            m_buttonFieldSupplier = initializer.getValueSupplier(getButtonFieldRefClass());
        }

        @Override
        public String computeState(final NodeParametersInput context) {
            return m_buttonFieldSupplier.get();
        }
    }

    /**
     * Base class for lock file validation message providers.
     * Shows warnings when lock file is missing or outdated.
     *
     * @param <ContentRef> the parameter reference type for content (TOML, YAML, or packages)
     * @param <LockFileRef> the parameter reference type for the hidden lock file field
     * @param <LockFileCopyProviderClass> the lock file copy provider class
     */
    static abstract class AbstractLockFileValidationProvider<ContentRef extends ParameterReference<?>,
            LockFileRef extends ParameterReference<String>,
            LockFileCopyProviderClass extends StateProvider<String>>
        implements StateProvider<Optional<TextMessage.Message>> {

        private Supplier<String> m_lockFileContentSupplier;
        private Supplier<?> m_contentSupplier;
        private Object m_lastValidContent;

        /**
         * Get the content reference class for initialization.
         */
        protected abstract Class<ContentRef> getContentRefClass();

        /**
         * Get the lock file reference class for initialization.
         */
        protected abstract Class<LockFileRef> getLockFileRefClass();

        /**
         * Get the lock file copy provider class for initialization.
         */
        protected abstract Class<LockFileCopyProviderClass> getLockFileCopyProviderClass();

        @Override
        public void init(final StateProviderInitializer initializer) {
            // Trigger on content changes, dialog open, and when lock file is updated
            initializer.computeOnValueChange(getContentRefClass());
            initializer.computeAfterOpenDialog();
            initializer.computeFromProvidedState(getLockFileCopyProviderClass());
            // Get suppliers for both lock file and content
            m_lockFileContentSupplier = initializer.getValueSupplier(getLockFileRefClass());
            m_contentSupplier = initializer.getValueSupplier(getContentRefClass());
        }

        @Override
        public Optional<TextMessage.Message> computeState(final NodeParametersInput context) {
            final String lockFileContent = m_lockFileContentSupplier.get();
            final Object currentContent = m_contentSupplier.get();

            // No lock file stored
            if (lockFileContent == null || lockFileContent.isBlank()) {
                m_lastValidContent = null;
                return Optional.of(new TextMessage.Message("Lock file status",
                    "Lock file not stored. Click 'Check compatibility' to verify environment reproducibility.",
                    MessageType.WARNING));
            }

            // Lock file exists - check if content changed since generation
            if (m_lastValidContent == null) {
                // First time we see a lock file - assume it's valid for current content
                m_lastValidContent = currentContent;
            }

            if (!contentEquals(m_lastValidContent, currentContent)) {
                return Optional.of(new TextMessage.Message("Lock file status",
                    "Lock file outdated (content changed). Click 'Check compatibility' to update.",
                    MessageType.WARNING));
            }

            return Optional.of(new TextMessage.Message("Lock file status",
                "Lock file saved (" + lockFileContent.length() + " bytes). Environment is reproducible.",
                MessageType.INFO));
        }

        /**
         * Compare content objects for equality. Override if content type needs custom comparison.
         */
        protected boolean contentEquals(final Object content1, final Object content2) {
            if (content1 == null || content2 == null) {
                return content1 == content2;
            }
            return content1.equals(content2);
        }
    }
}
