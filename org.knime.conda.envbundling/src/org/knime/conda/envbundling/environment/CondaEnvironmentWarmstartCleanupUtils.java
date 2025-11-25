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

import org.apache.commons.io.file.PathUtils;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IExtension;
import org.eclipse.core.runtime.Platform;
import org.knime.conda.envinstall.action.InstallCondaEnvironment;
import org.knime.core.node.NodeLogger;
import org.osgi.framework.Bundle;

/**
 * Utilities to cleanup unnecessary files after the installing Conda environments via the
 * {@link CondaEnvironmentWarmstartAction}.
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
 * @author Marc Lehner, KNIME AG, Zurich, Switzerland
 * @author Benjamin Wilhelm, KNIME GmbH, Berlin, Germany
 * @since 5.9
 */
final class CondaEnvironmentWarmstartCleanupUtils {

    private static final NodeLogger LOGGER = NodeLogger.getLogger(CondaEnvironmentWarmstartCleanupUtils.class);

    private CondaEnvironmentWarmstartCleanupUtils() {
    }

    /**
     * Cleans up the {@code env/channel/} and {@code env/pypi/} directories of <b>all</b> environment fragment bundles.
     */
    public static void cleanupFragmentDirectories() {
        LOGGER.debug("Scanning for fragment directories to clean up");

        var point = CondaEnvironmentRegistry.getExtensionPoint();
        for (IExtension extension : point.getExtensions()) {
            try {
                var bundle = Platform.getBundle(extension.getContributor().getName());
                cleanupFragmentDirectory(bundle);
            } catch (Exception e) { // NOSONAR - we cache everything to prevent failing
                LOGGER.warn(
                    "Failed to clean up fragment " + extension.getContributor().getName() + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * Deletes the {@code env/channel/} and {@code env/pypi/} directories from the given bundle.
     */
    private static void cleanupFragmentDirectory(final Bundle bundle) {
        if (bundle == null) {
            return;
        }

        // Locate the bundle's root directory
        var bundleLocation = FileLocator.getBundleFileLocation(bundle);
        if (bundleLocation.isEmpty()) {
            return;
        }

        var bundlePath = bundleLocation.get().toPath();
        var envFolder = bundlePath.resolve("env");

        if (!Files.isDirectory(envFolder)) {
            return;
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
    }

    /**
     * Cleans up the pixi cache directory in the bundling root.
     */
    public static void cleanupPixiCache() {
        try {
            var bundlingRoot = BundlingRoot.getInstance();
            var pixiCacheDir = bundlingRoot.getRoot().resolve(InstallCondaEnvironment.PIXI_CACHE_DIRECTORY_NAME);

            if (Files.isDirectory(pixiCacheDir)) {
                cleanupDirectory(pixiCacheDir, "pixi cache");
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
    private static void cleanupDirectory(final Path directory, final String description) {
        try {
            LOGGER.debug("Cleaning up " + description + ": " + directory);

            PathUtils.deleteDirectory(directory);

            LOGGER.debug("Successfully removed " + description);
        } catch (IOException e) {
            LOGGER.warn("Failed to remove " + description + ": " + directory + " - " + e.getMessage());
        }
    }
}
