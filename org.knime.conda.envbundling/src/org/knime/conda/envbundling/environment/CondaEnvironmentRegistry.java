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
 *   Mar 21, 2022 (benjamin): created
 */
package org.knime.conda.envbundling.environment;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IExtension;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;
import org.knime.conda.envbundling.CondaEnvironmentBundlingUtils;
import org.knime.core.node.NodeLogger;
import org.osgi.framework.Bundle;

/**
 * A registry for bundled conda channels.
 *
 * @author Benjamin Wilhelm, KNIME GmbH, Konstanz, Germany
 */
public final class CondaEnvironmentRegistry {

    private static final String EXT_POINT_ID = "org.knime.conda.envbundling.CondaEnvironment";

    /**
     * The old name of the folder containing the environment. Each fragment of a plugin which registers a
     * CondaEnvironment must have this folder at its root.
     */
    public static final String ENV_FOLDER_NAME = "env";

    /**
     * The name of the file that contains the path to the environment location
     *
     * @since 5.4
     */
    public static final String ENVIRONMENT_PATH_FILE = "environment_path.txt";

    /**
     * The new name of the folder containing the all conda environments.
     */
    public static final String BUNDLE_PREFIX = "bundling" + File.separator + "envs";

    private static final NodeLogger LOGGER = NodeLogger.getLogger(CondaEnvironmentRegistry.class);

    // Use AtomicReference to allow thread-safe invalidation and lazy initialization
    private static final AtomicReference<Map<String, CondaEnvironment>> m_environments = new AtomicReference<>(null);

    private CondaEnvironmentRegistry() {
        // Private constructor to prevent instantiation
    }

    /**
     * Invalidate the cached environments map. This should be called whenever an extension is installed or uninstalled.
     */
    public static void invalidateCache() {
        LOGGER.info("Invalidating CondaEnvironmentRegistry cache.");
        m_environments.set(null);
    }

    /**
     * Get the Conda environment with the given name.
     *
     * @param name the unique name of the requested environment
     * @return the {@link CondaEnvironment} which contains the path to the environment on disk
     */
    public static CondaEnvironment getEnvironment(final String name) {
        return getEnvironments().get(name);
    }

    /** @return a map of all environments that are installed. */
    public static Map<String, CondaEnvironment> getEnvironments() {
        if (m_environments.get() == null) {
            synchronized (m_environments) {
                if (m_environments.get() == null) {
                    LOGGER.info("Rebuilding CondaEnvironmentRegistry cache.");
                    m_environments.set(registerExtensions());
                }
            }
        }
        return m_environments.get();
    }

    /** Loop through extensions and collect them in a Map */
    private static Map<String, CondaEnvironment> registerExtensions() {
        final Map<String, CondaEnvironment> environments = new HashMap<>();
        final IExtensionRegistry registry = Platform.getExtensionRegistry();
        final IExtensionPoint point = registry.getExtensionPoint(EXT_POINT_ID);

        for (final IExtension ext : point.getExtensions()) {
            try {
                extractEnvironment(ext).ifPresent(env -> {
                    final var name = env.getName();
                    if (environments.containsKey(name)) {
                        LOGGER.errorWithFormat("An environment with the name '%s' is already registered. "
                            + "Please use a unique environment name.", name);
                    } else {
                        environments.put(env.getName(), env);
                    }
                });
            } catch (final Exception e) {
                LOGGER.error("An exception occurred while registering an extension at extension point '" + EXT_POINT_ID
                    + "'. Using Python nodes that require the environment will fail.", e);
            }
        }
        return Collections.unmodifiableMap(environments);
    }

    /** Extract the {@link CondaEnvironment} from the given extension */
    private static Optional<CondaEnvironment> extractEnvironment(final IExtension extension) {
        final String bundleName = extension.getContributor().getName();

        // Get the name of the environment
        final String name = extension.getLabel();
        if (name == null || name.isBlank()) {
            LOGGER.errorWithFormat("The name of the Conda environment defined by the plugin '%s' is missing. "
                + "Please specify a unique name.", bundleName);
            return Optional.empty();
        }

        // Get the path to the environment
        final var bundle = Platform.getBundle(bundleName);
        final Bundle[] fragments = Platform.getFragments(bundle);

        if (fragments.length < 1) {
            LOGGER.errorWithFormat(
                "Could not find a platform-specific fragment for the bundled Conda environment in plugin '%s' "
                    + "(operating system: %s, system architecture: %s).",
                bundle, Platform.getOS(), Platform.getOSArch());
            return Optional.empty();
        }
        if (fragments.length > 1) {
            final String usedFragmentName = fragments[0].getSymbolicName();
            final String unusedFragmentNames =
                Arrays.stream(fragments).skip(1).map(Bundle::getSymbolicName).collect(Collectors.joining(", "));
            LOGGER.warnWithFormat(
                "Found %d platform specific fragments for the bundled Conda environment in plugin '%s' "
                    + "(operating system: %s, system architecture: %s). "
                    + "The fragment '%s' will be used. The fragments [%s] will be ignored.",
                fragments.length, bundleName, Platform.getOS(), Platform.getOSArch(), usedFragmentName,
                unusedFragmentNames);
        }

        Path path = null;

        String bundleLocationString = FileLocator.getBundleFileLocation(fragments[0]).orElseThrow().getAbsolutePath();
        Path bundleLocationPath = Paths.get(bundleLocationString);
        Path installationDirectoryPath = bundleLocationPath.getParent().getParent();

        // try to find environment_path.txt, if that is present, use that.
        try {
            var environmentPathFile =
                CondaEnvironmentBundlingUtils.getAbsolutePath(fragments[0], ENVIRONMENT_PATH_FILE);
            var environmentPath = FileUtils.readFileToString(environmentPathFile.toFile(), StandardCharsets.UTF_8);
            environmentPath = environmentPath.trim();
            // Note: if environmentPath is absolute, resolve returns environmentPath directly
            path = installationDirectoryPath.resolve(environmentPath);
            LOGGER.debug("Found environment path '" + path + "' (before expansion: '" + environmentPath + "') for '"
                + bundleName + "' in '" + environmentPathFile + "'");

        } catch (IOException e) {
            // No problem, we only introduced the environment_path.txt file in 5.4. Trying other env locations...
            LOGGER.debug("No " + ENVIRONMENT_PATH_FILE + " file found for '" + bundleName + "'");

            String knimePythonBundlingPath = System.getenv("KNIME_PYTHON_BUNDLING_PATH");
            if (knimePythonBundlingPath != null) {
                path = Paths.get(knimePythonBundlingPath, name);
                LOGGER.debug("KNIME_PYTHON_BUNDLING_PATH is set, expecting environment at '" + path + "' for '"
                    + bundleName + "'");
            } else {
                path = installationDirectoryPath.resolve(BUNDLE_PREFIX).resolve(name);
            }
        }

        if (!Files.exists(path)) {
            try {
                path = CondaEnvironmentBundlingUtils.getAbsolutePath(fragments[0], ENV_FOLDER_NAME);
                LOGGER.debug("Found environment for '" + bundleName + "' inside plugin folder: " + path);
            } catch (final IOException ex) {
                LOGGER.error(String.format("Could not find the path to the Conda environment for the plugin '%s'. "
                    + "Did the installation of the plugin fail?", bundleName), ex);
                return Optional.empty();
            }
        }

        return Optional.of(new CondaEnvironment(bundle, path, name, requiresDownload(extension)));

    }

    private static boolean requiresDownload(final IExtension extension) {
        return Arrays.stream(extension.getConfigurationElements())
            .anyMatch(e -> "requires-download".equals(e.getName()));
    }
}
