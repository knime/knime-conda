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
package org.knime.conda.envbundling.environment2;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Platform;
import org.knime.conda.envbundling.environment2.CondaEnvironmentRegistry2.CondaEnvironmentExtension;
import org.knime.conda.envinstall.action.InstallCondaEnvironment;
import org.osgi.framework.Bundle;

/**
 * Manages the installation and deletion of Conda environments.
 *
 * TODO move to registry???
 *
 * @author Benjamin Wilhelm, KNIME GmbH, Berlin, Germany
 */
public class CondaEnvironmentManager {

    private static CondaEnvironmentManager INSTANCE;

    private Map<String, CondaEnvironment2> m_environments;

    public static CondaEnvironmentManager getInstance() {
        if (INSTANCE == null) {
            initialize();
        }
        return INSTANCE;
    }

    public static synchronized void initialize() {
        if (INSTANCE != null) {
            return;
        }

        var executor = Executors.newSingleThreadExecutor();

        // Start creation of all registered Conda environment extensions
        var extensions = CondaEnvironmentRegistry2.collectExtensions();
        var environments = new HashMap<String, CondaEnvironment2>();
        for (var ext : extensions) {
            var path = resolveOrInstallCondaEnvironment(ext, executor);
            environments.put(ext.name(), new CondaEnvironment2(ext, path));
        }

        INSTANCE = new CondaEnvironmentManager(environments);

        // TODO look into the condaRoot folder and delete environments that are not in the registry anymore
    }

    private CondaEnvironmentManager(final HashMap<String, CondaEnvironment2> environments) {
        m_environments = Collections.unmodifiableMap(environments);
    }

    private static Future<Path> resolveOrInstallCondaEnvironment(final CondaEnvironmentExtension ext,
        final ExecutorService executor) {
        var condaRoot = CondaEnvironmentRoot.CONDA_ENVIRONMENTS_ROOT;
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
}
