package org.knime.pixi.nodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.knime.conda.envinstall.pixi.PixiBinary;
import org.knime.core.node.NodeLogger;
import org.knime.core.webui.node.dialog.defaultdialog.internal.button.CancelableActionHandler;
import org.knime.core.webui.node.dialog.defaultdialog.widget.handler.WidgetHandlerException;
import org.knime.node.parameters.NodeParametersInput;
import org.knime.node.parameters.updates.EffectPredicate;
import org.knime.node.parameters.updates.EffectPredicateProvider;
import org.knime.node.parameters.updates.ParameterReference;
import org.knime.node.parameters.updates.StateProvider;
import org.knime.node.parameters.widget.message.TextMessage;
import org.knime.node.parameters.widget.message.TextMessage.MessageType;
import org.knime.pixi.port.PixiUtils;

/**
 * Utility class for reducing code duplication in Pixi parameter classes.
 *
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 * @since 5.11
 */
@SuppressWarnings("restriction")
final class PixiParameterUtils {

    private static final NodeLogger LOGGER = NodeLogger.getLogger(PixiParameterUtils.class);

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
            LOGGER.debug(m_logPrefix + " Button clicked - running pixi lock...");

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
                    LOGGER.warn(m_logPrefix + " Pixi lock failed: " + errorDetails);
                    throw new WidgetHandlerException("Pixi lock failed:\n" + errorDetails);
                }

                // Read the generated lock file
                final Path lockFilePath = projectDir.resolve("pixi.lock");
                if (Files.exists(lockFilePath)) {
                    final String lockContent = Files.readString(lockFilePath);
                    LOGGER.debug(m_logPrefix + " Lock file generated (" + lockContent.length() + " bytes)");
                    return lockContent;
                } else {
                    throw new WidgetHandlerException("Lock file was not generated at: " + lockFilePath);
                }
            } catch (WidgetHandlerException e) {
                throw e;
            } catch (Exception ex) {
                LOGGER.error(m_logPrefix + " Failed to generate lock file: " + ex.getMessage(), ex);
                throw new WidgetHandlerException("Failed to generate lock file: " + ex.getMessage());
            }
        }

        @Override
        protected String getButtonText(final States state) {
            return switch (state) {
                case READY -> "Lock Environment";
                case CANCEL -> "Cancel";
                case DONE -> "Re-lock Environment";
            };
        }

        @Override
        protected boolean isMultiUse() {
            return true; // Allow re-checking
        }
    }

    /**
     * Base class for button action handlers that update packages using pixi update.
     * Updates all dependencies and generates a new lock file.
     *
     * @param <ContentGetter> a functional interface that extracts the manifest content from dependencies
     */
    static abstract class AbstractPixiUpdateActionHandler<ContentGetter>
        extends CancelableActionHandler<String, ContentGetter> {

        private final String m_logPrefix;

        /**
         * Constructor.
         *
         * @param logPrefix prefix for console logging (e.g., "[PixiToml]" or "[PixiYaml]")
         */
        protected AbstractPixiUpdateActionHandler(final String logPrefix) {
            m_logPrefix = logPrefix;
        }

        /**
         * Extract the manifest content from the dependency object.
         */
        protected abstract String getManifestContent(ContentGetter contentGetter);

        /**
         * Prepare the manifest content for pixi update. For TOML, this is a no-op. For YAML/packages, convert to TOML.
         *
         * @param content the raw content
         * @return the TOML manifest content ready for pixi
         * @throws Exception if conversion/preparation fails
         */
        protected abstract String prepareManifestContent(String content) throws Exception;

        @Override
        protected String invoke(final ContentGetter settings, final NodeParametersInput context)
            throws WidgetHandlerException {
            LOGGER.debug(m_logPrefix + " Button clicked - running pixi update...");

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
                final String[] pixiArgs = {"--color", "never", "--no-progress", "update"};

                final var callResult = PixiBinary.callPixi(projectDir, extraEnv, pixiArgs);

                if (callResult.returnCode() != 0) {
                    String errorDetails = PixiUtils.getMessageFromCallResult(callResult);
                    LOGGER.warn(m_logPrefix + " Pixi update failed: " + errorDetails);
                    throw new WidgetHandlerException("Pixi update failed:\n" + errorDetails);
                }

                // Read the updated lock file
                final Path lockFilePath = projectDir.resolve("pixi.lock");
                if (Files.exists(lockFilePath)) {
                    final String lockContent = Files.readString(lockFilePath);
                    LOGGER.debug(m_logPrefix + " Lock file updated (" + lockContent.length() + " bytes)");
                    return lockContent;
                } else {
                    throw new WidgetHandlerException("Lock file was not found after update at: " + lockFilePath);
                }
            } catch (WidgetHandlerException e) {
                throw e;
            } catch (Exception ex) {
                LOGGER.error(m_logPrefix + " Failed to update environment: " + ex.getMessage(), ex);
                throw new WidgetHandlerException("Failed to update environment: " + ex.getMessage());
            }
        }

        @Override
        protected String getButtonText(final States state) {
            return switch (state) {
                case READY -> "Update Dependencies";
                case CANCEL -> "Cancel";
                case DONE -> "Update Again";
            };
        }

        @Override
        protected boolean isMultiUse() {
            return true; // Allow re-updating
        }
    }

    /**
     * State provider that copies lock file content from button, but resets to empty string when content changes.
     * This enables Effect-based visibility control: message shown when lock file is empty, hidden otherwise.
     *
     * @param <ContentRef> the parameter reference for content (TOML, YAML, or packages)
     * @param <ButtonFieldRef> the button field reference
     */
    static abstract class AbstractResetLockFileProvider<ContentRef extends ParameterReference<?>,
            ButtonFieldRef extends ParameterReference<String>>
        implements StateProvider<String> {

        private Supplier<String> m_buttonFieldSupplier;
        private Supplier<?> m_contentSupplier;
        private Object m_lastContent;

        /**
         * Get the content reference class.
         */
        protected abstract Class<ContentRef> getContentRefClass();

        /**
         * Get the button field reference class.
         */
        protected abstract Class<ButtonFieldRef> getButtonFieldRefClass();

        @Override
        public void init(final StateProviderInitializer initializer) {
            initializer.computeOnValueChange(getButtonFieldRefClass());
            initializer.computeOnValueChange(getContentRefClass());
            m_buttonFieldSupplier = initializer.getValueSupplier(getButtonFieldRefClass());
            m_contentSupplier = initializer.getValueSupplier(getContentRefClass());
        }

        @Override
        public String computeState(final NodeParametersInput context) {
            final Object currentContent = m_contentSupplier.get();
            final String buttonValue = m_buttonFieldSupplier.get();

            // If content changed, reset lock file to empty (invalidates previous lock)
            if (m_lastContent != null && !contentEquals(m_lastContent, currentContent)) {
                m_lastContent = currentContent;
                return ""; // Reset to empty when content changes
            }

            m_lastContent = currentContent;
            return buttonValue != null ? buttonValue : "";
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

    /**
     * Validation message provider for lock file with Effect-based visibility.
     * Always returns the same warning message. Visibility is controlled by an Effect with LockFileIsEmpty predicate.
     * Use @Effect(predicate = LockFileIsEmpty.class, type = EffectType.SHOW) on the message field.
     *
     * @param <LockFileRef> the lock file reference
     */
    static abstract class AbstractLockFileValidationWithEffectProvider<LockFileRef extends ParameterReference<String>>
        implements StateProvider<Optional<TextMessage.Message>> {

        /**
         * Get the lock file reference class.
         */
        protected abstract Class<LockFileRef> getLockFileRefClass();

        @Override
        public void init(final StateProviderInitializer initializer) {
            // Depend on lock file content to trigger visibility updates via Effect
            initializer.computeOnValueChange(getLockFileRefClass());
            initializer.computeAfterOpenDialog();
        }

        @Override
        public Optional<TextMessage.Message> computeState(final NodeParametersInput context) {
            return Optional.of(new TextMessage.Message("Lock file status",
                "Please click 'Lock Environment' to generate the lock file for reproducibility.",
                MessageType.WARNING));
        }
    }

    /**
     * Predicate that returns true when the lock file content is empty.
     * Used with @Effect(predicate = LockFileIsEmpty.class, type = EffectType.SHOW) to show message when empty.
     *
     * @param <LockFileRef> the lock file reference
     */
    static abstract class AbstractLockFileIsEmptyPredicate<LockFileRef extends ParameterReference<String>>
        implements EffectPredicateProvider {

        /**
         * Get the lock file reference class.
         */
        protected abstract Class<LockFileRef> getLockFileRefClass();

        @Override
        public EffectPredicate init(final PredicateInitializer i) {
            return i.getString(getLockFileRefClass()).isEqualTo("");
        }
    }
}
