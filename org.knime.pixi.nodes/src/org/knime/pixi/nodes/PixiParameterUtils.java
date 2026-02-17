package org.knime.pixi.nodes;

import java.nio.file.Files;
import java.nio.file.Path;

import org.knime.conda.envinstall.pixi.PixiBinary;
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.NodeSettingsRO;
import org.knime.core.node.NodeSettingsWO;
import org.knime.core.util.PathUtils;
import org.knime.core.webui.node.dialog.defaultdialog.internal.button.CancelableActionHandler;
import org.knime.core.webui.node.dialog.defaultdialog.widget.handler.WidgetHandlerException;
import org.knime.node.parameters.NodeParametersInput;
import org.knime.node.parameters.persistence.NodeParametersPersistor;
import org.knime.pixi.port.PixiUtils;

/**
 * Utility class for reducing code duplication in Pixi parameter classes. Provides base classes for common button action
 * handlers and shared persistors.
 *
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 * @author Marc Lehner, KNIME GmbH, Zurich, Switzerland
 * @since 5.11
 */
@SuppressWarnings("restriction")
final class PixiParameterUtils {

    //private static final NodeLogger LOGGER = NodeLogger.getLogger(PixiParameterUtils.class);

    private PixiParameterUtils() {
        // Utility class
    }

    /**
     * Base class for button action handlers that run pixi lock to generate a lock file. Validates the manifest by
     * generating a lock file with resolved dependencies.
     *
     * @param <ContentGetter> a functional interface that extracts the manifest content from dependencies
     */
    static abstract class AbstractPixiLockActionHandler<ContentGetter>
        extends CancelableActionHandler<String, ContentGetter> {

        private volatile boolean m_cancelled = false;

        /**
         * Constructor.
         */
        protected AbstractPixiLockActionHandler() {
        }

        /**
         * Extract the manifest content from the dependency object.
         */
        protected abstract String getManifestContent(ContentGetter contentGetter) throws Exception;

        protected void onCancel() {
            // Called when user clicks the Cancel button
            m_cancelled = true;
        }

        @Override
        protected String invoke(final ContentGetter settings, final NodeParametersInput context)
            throws WidgetHandlerException {

            // Reset cancellation flag at start of each invocation
            m_cancelled = false;

            Path projectDir = null;
            try {
                final String tomlContent = getManifestContent(settings);
                if (tomlContent == null || tomlContent.isBlank()) {
                    throw new WidgetHandlerException("No manifest content provided");
                }

                // Always use a fresh temp directory during configuration
                projectDir = PathUtils.createTempDir("pixi-envs-config");

                // Write the TOML manifest to the temp directory
                final Path tomlFilePath = projectDir.resolve("pixi.toml");
                Files.writeString(tomlFilePath, tomlContent);

                // Run pixi lock to resolve dependencies and generate lock file
                final String[] pixiArgs = {"--color", "never", "--no-progress", "lock"};
                final var callResult =
                    PixiBinary.callPixiWithCancellation(projectDir, null, () -> m_cancelled, pixiArgs);

                if (callResult.returnCode() != 0) {
                    String errorDetails = PixiUtils.getMessageFromCallResult(callResult);
                    throw new WidgetHandlerException("Pixi lock failed:\n" + errorDetails);
                }

                // Read the generated lock file
                final Path lockFilePath = projectDir.resolve("pixi.lock");
                if (Files.exists(lockFilePath)) {
                    final String lockContent = Files.readString(lockFilePath);
                    return lockContent;
                } else {
                    throw new WidgetHandlerException("Lock file was not generated at: " + lockFilePath);
                }
            } catch (WidgetHandlerException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WidgetHandlerException("Lock generation was cancelled");
            } catch (Exception ex) {
                throw new WidgetHandlerException("Failed to generate lock file: " + ex.getMessage());
            } finally {
                // Clean up temp directory
                try {
                    PathUtils.deleteDirectoryIfExists(projectDir);
                } catch (Exception e) {
                    // Best effort cleanup - log but don't fail
                }
            }
        }

        @Override
        protected String getButtonText(final States state) {
            return switch (state) {
                case READY -> "Resolve Dependencies";
                case CANCEL -> "Cancel";
                case DONE -> "Resolve Dependencies";
            };
        }

        @Override
        protected boolean isMultiUse() {
            return true; // Allow multiple clicks to update the lock file
        }
    }

    /**
     * Custom persistor that doesn't persist the field - used for computed/display-only fields. This is useful for
     * fields that are calculated or derived from other fields and should not be saved to settings.
     */
    static final class DoNotPersist implements NodeParametersPersistor<String> {
        @Override
        public String load(final NodeSettingsRO settings) throws InvalidSettingsException {
            return null;
        }

        @Override
        public void save(final String obj, final NodeSettingsWO settings) {
            // Don't persist - this is a computed field
        }

        @Override
        public String[][] getConfigPaths() {
            return new String[0][];
        }
    }
}
