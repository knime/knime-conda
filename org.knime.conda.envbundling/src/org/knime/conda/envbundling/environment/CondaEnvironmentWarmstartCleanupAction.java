/*
 * ------------------------------------------------------------------------
 *
 *  Copyright by KNIME AG, Zurich, Switzerland
 *  Website: http://www.knime.com; Email: contact@knime.com
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License, Version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 *  Additional permission under GNU GPL version 3 section 7:
 *
 *  KNIME interoperates with ECLIPSE solely via ECLIPSE's plug-in APIs.
 *  Hence, KNIME and ECLIPSE are both independent programs and are not
 *  derived from each other. Should, however, the interpretation of the
 *  GNU GPL Version 3 ("License") under any applicable laws result in
 *  KNIME and ECLIPSE being a combined program, KNIME AG herewith grants
 *  you the additional permission to use and propagate KNIME together with
 *  ECLIPSE with only the license terms in place for ECLIPSE applying to
 *  ECLIPSE and the GNU GPL Version 3 applying for KNIME, provided the
 *  license terms of ECLIPSE themselves allow for the respective use and
 *  propagation of ECLIPSE together with KNIME.
 *
 *  Additional permission relating to nodes for KNIME that extend the Node
 *  Extension (and in particular that are based on subclasses of NodeModel,
 *  NodeDialog, and NodeView) and that only interoperate with KNIME through
 *  standard APIs ("Nodes"):
 *  Nodes are deemed to be separate and independent programs and to not be
 *  covered works.  Notwithstanding anything to the contrary in the
 *  License, the License does not apply to Nodes, you are not required to
 *  license Nodes under the License, and you are granted a license to
 *  prepare and propagate Nodes, in each case even if such Nodes are
 *  propagated with or for interoperation with KNIME.  The owner of a Node
 *  may freely choose the license terms applicable to such Node, including
 *  when such Node is propagated with or for interoperation with KNIME.
 * ---------------------------------------------------------------------
 *
 * History
 *   Nov 25, 2025 (Marc Lehner): created
 */
package org.knime.conda.envbundling.environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.apache.commons.io.file.PathUtils;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IExtension;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;
import org.knime.core.node.NodeLogger;
import org.knime.product.headless.IWarmstartAction;
import org.osgi.framework.Bundle;

/**
 * Warmstart action that cleans up temporary files used during conda environment installation.
 *
 * <p>
 * After environments are installed via {@link CondaEnvironmentWarmstartAction}, the installation artifacts are no
 * longer needed in a docker environment and can be safely removed to save disk space:
 * </p>
 * <ul>
 * <li><strong>Channel directories</strong> – Local conda package channels in fragment {@code env/channel/} folders</li>
 * <li><strong>PyPI directories</strong> – Local PyPI indices in fragment {@code env/pypi/} folders</li>
 * <li><strong>Pixi cache</strong> – The {@code .pixi-cache} directory in the bundling root used during
 * installation</li>
 * </ul>
 *
 * <p>
 * This action should typically run <em>after</em> {@link CondaEnvironmentWarmstartAction} to ensure environments are
 * fully installed before cleanup begins.
 * </p>

 *
 * @author Marc Lehner, KNIME AG, Zurich, Switzerland
 * @since 5.9
 */
public class CondaEnvironmentWarmstartCleanupAction implements IWarmstartAction {

    private static final NodeLogger LOGGER = NodeLogger.getLogger(CondaEnvironmentWarmstartCleanupAction.class);

    private static final String EXT_POINT_ID = "org.knime.conda.envbundling.CondaEnvironment";

    private static final String PIXI_CACHE_DIRECTORY_NAME = ".pixi-cache";

    /**
     * Default constructor required for extension point instantiation.
     */
    public CondaEnvironmentWarmstartCleanupAction() {
        // Default constructor
    }

    @Override
    public WarmstartResult execute() throws Exception {
        LOGGER.info("Starting conda environment cleanup");

        try {
            // Step 1: Verify environments are installed
            Map<String, CondaEnvironment> environments = CondaEnvironmentRegistry.getEnvironments();
            if (environments == null || environments.isEmpty()) {
                LOGGER.info("No conda environments found - skipping cleanup");
                return WarmstartResult.success("No cleanup needed: no environments installed");
            }

            LOGGER.info("Found " + environments.size() + " installed environment(s), proceeding with cleanup");

            // Step 2: Clean up channel and PyPI directories in fragments
            cleanupFragmentDirectories();

            // Step 3: Clean up pixi cache
            cleanupPixiCache();

            LOGGER.info("Conda environment cleanup completed successfully");
            return WarmstartResult.success("Conda environment cleanup completed successfully");

        } catch (Exception e) {
            LOGGER.error("Failed to complete conda environment cleanup", e);
            return WarmstartResult.failure("Conda environment cleanup failed: " + e.getMessage(), e);
        }
    }

    /**
     * Cleans up the {@code env/channel/} and {@code env/pypi/} directories inside environment fragment bundles.
     */
    private void cleanupFragmentDirectories() {
        LOGGER.debug("Scanning for fragment directories to clean up");

        IExtensionRegistry registry = Platform.getExtensionRegistry();
        IExtensionPoint point = registry.getExtensionPoint(EXT_POINT_ID);

        if (point == null) {
            LOGGER.warn("Extension point " + EXT_POINT_ID + " not found");
            return;
        }

        for (IExtension extension : point.getExtensions()) {
            try {
                Bundle bundle = Platform.getBundle(extension.getContributor().getName());
                if (bundle == null) {
                    continue;
                }

                // Locate the bundle's root directory
                var bundleLocation = FileLocator.getBundleFileLocation(bundle);
                if (bundleLocation.isEmpty()) {
                    continue;
                }

                Path bundlePath = bundleLocation.get().toPath();
                Path envFolder = bundlePath.resolve("env");

                if (!Files.isDirectory(envFolder)) {
                    continue;
                }

                // Clean up channel directory
                Path channelDir = envFolder.resolve("channel");
                if (Files.isDirectory(channelDir)) {
                    cleanupDirectory(channelDir, "channel directory in " + bundle.getSymbolicName());
                }

                // Clean up pypi directory
                Path pypiDir = envFolder.resolve("pypi");
                if (Files.isDirectory(pypiDir)) {
                    cleanupDirectory(pypiDir, "PyPI directory in " + bundle.getSymbolicName());
                }

            } catch (Exception e) {
                LOGGER.warn(
                    "Failed to clean up fragment " + extension.getContributor().getName() + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * Cleans up the pixi cache directory in the bundling root.
     */
    private void cleanupPixiCache() {
        try {
            BundlingRoot bundlingRoot = BundlingRoot.getInstance();
            Path pixiCacheDir = bundlingRoot.getRoot().resolve(PIXI_CACHE_DIRECTORY_NAME);

            if (Files.isDirectory(pixiCacheDir)) {
                LOGGER.info("Cleaning up pixi cache directory: " + pixiCacheDir);
                try {
                    PathUtils.deleteDirectory(pixiCacheDir);
                    LOGGER.info("Successfully removed pixi cache");
                } catch (IOException e) {
                    LOGGER.error("Failed to remove pixi cache directory: " + pixiCacheDir, e);
                }
            } else {
                LOGGER.debug("Pixi cache directory does not exist, skipping: " + pixiCacheDir);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to access bundling root for cache cleanup", e);
        }
    }

    /**
     * Cleans up a single directory
     */
    private void cleanupDirectory(final Path directory, final String description) {
        try {
            LOGGER.debug("Cleaning up " + description + ": " + directory);

            PathUtils.deleteDirectory(directory);

            LOGGER.debug("Successfully removed " + description);
        } catch (IOException e) {
            LOGGER.warn("Failed to remove " + description + ": " + directory + " - " + e.getMessage());
        }
    }
}
