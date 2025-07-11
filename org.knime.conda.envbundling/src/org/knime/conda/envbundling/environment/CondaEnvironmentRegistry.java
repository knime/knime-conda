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
 *   Jul 11, 2025 (benjaminwilhelm): created
 */
package org.knime.conda.envbundling.environment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IExtension;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;
import org.knime.conda.envinstall.action.InstallCondaEnvironment;
import org.knime.core.node.NodeLogger;
import org.osgi.framework.Bundle;

/**
 *
 * @author Benjamin Wilhelm, KNIME GmbH, Berlin, Germany
 */
public final class CondaEnvironmentRegistry {

    // TODO restructure?

    // TODO get a better path
    // NOTE: Either it should be separate per installation or the deletion/re-creation of the environments should be less strict
    private static final Path CONDA_ENVIRONMENTS_ROOT =
        Path.of("/Users/benjaminwilhelm/misc/tmp_knime_conda_envs_root");

    private static final String EXT_POINT_ID = "org.knime.conda.envbundling.CondaEnvironment";

    private static final NodeLogger LOGGER = NodeLogger.getLogger(CondaEnvironmentRegistry.class);

    private static CondaEnvironmentRegistry INSTANCE;

    private final Map<String, CondaEnvironment> m_environments;

    public static CondaEnvironmentRegistry getInstance() {
        if (INSTANCE == null) {
            initialize();
        }
        return INSTANCE;
    }

    private static synchronized void initialize() {
        if (INSTANCE != null) {
            return;
        }

        var executor = Executors.newSingleThreadExecutor();

        // Start creation of all registered Conda environment extensions
        var extensions = CondaEnvironmentRegistry.collectExtensions();
        var environments = new HashMap<String, CondaEnvironment>();
        for (var ext : extensions) {
            var path = resolveOrInstallCondaEnvironment(ext, executor);
            environments.put(ext.name(), new CondaEnvironment(ext, path));
        }

        INSTANCE = new CondaEnvironmentRegistry(environments);

        // TODO look into the condaRoot folder and delete environments that are not in the registry anymore
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
        return getInstance().m_environments;
    }

    private CondaEnvironmentRegistry(final HashMap<String, CondaEnvironment> environments) {
        m_environments = Collections.unmodifiableMap(environments);
    }

    private static Future<Path> resolveOrInstallCondaEnvironment(final CondaEnvironmentExtension ext,
        final ExecutorService executor) {
        var condaRoot = CONDA_ENVIRONMENTS_ROOT;
        var environmentRoot = condaRoot.resolve(ext.name());
        if (Files.exists(environmentRoot)) {
            // TODO check if the environment is still up-to-date
            return CompletableFuture
                .completedFuture(environmentRoot.resolve(".pixi").resolve("envs").resolve("default"));
        }

        // Install the environment
        return executor.submit(() -> InstallCondaEnvironment
            .installCondaEnvironment(findEnvironmentDefinition(ext.bundle()), ext.name(), condaRoot));
    }

    // Return the path to the fragment that contains the pixi files and packages
    private static Path findEnvironmentDefinition(final Bundle bundle) throws CondaInstallationException {
        var fragment = getFragmentBundle(bundle);
        String bundleLocationString = FileLocator.getBundleFileLocation(fragment).orElseThrow().getAbsolutePath();
        return Paths.get(bundleLocationString);
    }

    private static Bundle getFragmentBundle(final Bundle bundle) throws CondaInstallationException {
        final Bundle[] fragments = Platform.getFragments(bundle);

        if (fragments.length < 1) {
            throw new CondaInstallationException(String.format(
                "Could not find a platform-specific fragment for the bundled Conda environment in plugin '%s' "
                    + "(operating system: %s, system architecture: %s).",
                bundle, Platform.getOS(), Platform.getOSArch()));
        }
        if (fragments.length > 1) {
            final String usedFragmentName = fragments[0].getSymbolicName();
            final String unusedFragmentNames =
                Arrays.stream(fragments).skip(1).map(Bundle::getSymbolicName).collect(Collectors.joining(", "));
            throw new CondaInstallationException(String.format(
                "Found %d platform specific fragments for the bundled Conda environment in plugin '%s' "
                    + "(operating system: %s, system architecture: %s). "
                    + "The fragment '%s' will be used. The fragments [%s] will be ignored.",
                fragments.length, bundle.getSymbolicName(), Platform.getOS(), Platform.getOSArch(), usedFragmentName,
                unusedFragmentNames));
        }
        return fragments[0];
    }

    // TODO do we really need this?
    private static final class CondaInstallationException extends Exception {
        private static final long serialVersionUID = 1L;

        public CondaInstallationException(final String message) {
            super(message);
        }

        public CondaInstallationException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Record to represent extension information. TODO describe params
     */
    public static record CondaEnvironmentExtension(Bundle bundle, String name, boolean requiresDownload) {
    }

    /**
     * Collects all "CondaEnvironment" extensions.
     *
     * @return a list of {@link CondaEnvironmentExtension} representing the extensions.
     */
    private static List<CondaEnvironmentExtension> collectExtensions() {
        List<CondaEnvironmentExtension> extensions = new ArrayList<>();
        IExtensionRegistry registry = Platform.getExtensionRegistry();
        IExtensionPoint point = registry.getExtensionPoint(EXT_POINT_ID);

        if (point == null) {
            LOGGER.warn("Extension point " + EXT_POINT_ID + " not found.");
            return extensions;
        }

        for (IExtension ext : point.getExtensions()) {
            Optional<CondaEnvironmentExtension> extensionInfo = extractExtensionInfo(ext);
            extensionInfo.ifPresent(extensions::add);
        }

        return extensions;
    }

    /**
     * Extracts the {@link CondaEnvironmentExtension} from the given extension.
     *
     * @param extension the extension to extract information from.
     * @return an {@link Optional} containing the extracted information, or empty if invalid.
     */
    private static Optional<CondaEnvironmentExtension> extractExtensionInfo(final IExtension extension) {
        var bundleName = extension.getContributor().getName();
        var bundle = Platform.getBundle(bundleName);

        var requiresDownload =
            Arrays.stream(extension.getConfigurationElements()).anyMatch(e -> "requires-download".equals(e.getName()));

        var envName = extension.getLabel();
        if (envName == null || envName.isBlank()) {
            LOGGER.errorWithFormat(
                "The name of the Conda environment defined by the plugin '%s' is missing. " + "Specify a unique name.",
                bundleName);
            return Optional.empty();
        }

        return Optional.of(new CondaEnvironmentExtension(bundle, envName, requiresDownload));
    }
}
