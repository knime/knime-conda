package org.knime.pixi.nodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.knime.conda.envinstall.pixi.PixiBinary;
import org.knime.core.node.NodeLogger;
import org.knime.core.webui.node.dialog.defaultdialog.internal.button.CancelableActionHandler;
import org.knime.core.webui.node.dialog.defaultdialog.widget.handler.WidgetHandlerException;
import org.knime.node.parameters.NodeParametersInput;
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

    // TODO join with implementation in PythonEnvironmentNodeParameters
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
                final Path projectDir = PixiUtils.resolveProjectDirectory(manifestContent);
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
}
