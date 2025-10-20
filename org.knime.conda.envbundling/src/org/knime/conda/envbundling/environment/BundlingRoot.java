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
 *   Jul 22, 2025 (benjaminwilhelm): created
 */
package org.knime.conda.envbundling.environment;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Platform;

/**
 * Provides the root directory where bundled Conda environments are stored.
 * <p>
 * Singleton with lazy initialization. Initialization errors are cached and re-thrown on subsequent calls to avoid
 * repeated work and noisy logs.
 * <p>
 * Uses the same bundling root logic as {@code InstallCondaEnvironment.getBundlingRoot()}, which respects the
 * {@code KNIME_PYTHON_BUNDLING_PATH} environment variable.
 *
 * @author Benjamin Wilhelm, KNIME GmbH, Berlin, Germany
 */
final class BundlingRoot {

    /** The successfully initialized instance (if any). */
    private static volatile BundlingRoot INSTANCE;

    /** The initialization failure (if any), cached to avoid retrying. */
    private static volatile IOException INIT_FAILURE;

    private final Path m_bundlingRoot;

    private BundlingRoot(final Path bundlingRoot) {
        m_bundlingRoot = bundlingRoot;
    }

    /**
     * Returns the singleton instance. Lazily initializes it on first call.
     *
     * @throws IOException if the bundling root cannot be determined or created.
     */
    static BundlingRoot getInstance() throws IOException {
        // Fast path: no synchronization if already initialized or previously failed
        BundlingRoot inst = INSTANCE;
        if (inst != null) {
            return inst;
        }
        if (INIT_FAILURE != null) {
            throw INIT_FAILURE;
        }

        synchronized (BundlingRoot.class) {
            // Re-check inside synchronized block
            inst = INSTANCE;
            if (inst != null) {
                return inst;
            }
            if (INIT_FAILURE != null) {
                throw INIT_FAILURE;
            }

            try {
                Path root = getBundlingRootPath();
                inst = new BundlingRoot(root);
                INSTANCE = inst;
                return inst;

            } catch (IOException e) {
                INIT_FAILURE = e; // remember failure
                throw e;
            }
        }
    }

    /**
     * Determines the bundling root path by implementing the same logic as InstallCondaEnvironment.getBundlingRoot().
     * This ensures startup-created environments use the exact same location as install-time environments.
     */
    private static Path getBundlingRootPath() throws IOException {
        Path installationRoot = getInstallationRoot();
        
        // Implement the same logic as InstallCondaEnvironment.getBundlingRoot()
        var bundlingPathFromVar = System.getenv("KNIME_PYTHON_BUNDLING_PATH");
        Path path;
        if (bundlingPathFromVar != null && !bundlingPathFromVar.isBlank()) {
            path = Paths.get(bundlingPathFromVar);
        } else {
            path = installationRoot.resolve("bundling").toAbsolutePath();
        }

        if (Files.notExists(path)) {
            try {
                Files.createDirectories(path);
            } catch (IOException ioe) {
                throw new IOException("Unable to create bundling directory " + path + ": " + ioe.getMessage(), ioe);
            }
        }
        
        return path;
    }

    /**
     * Determines the KNIME installation root directory.
     */
    private static Path getInstallationRoot() throws IOException {
        try {
            var bundle = Platform.getBundle("org.knime.conda.envbundling");
            String bundleLocationString = FileLocator.getBundleFileLocation(bundle).orElseThrow().getAbsolutePath();
            Path bundleLocationPath = Paths.get(bundleLocationString);
            return bundleLocationPath.getParent().getParent();

        } catch (Exception e) {
            throw new IOException("Failed to determine installation root: " + e.getMessage(), e);
        }
    }

    Path getRoot() {
        return m_bundlingRoot;
    }

    /**
     * Returns the directory where the given environment will be stored.
     *
     * @param environmentName the name of the environment
     */
    Path getEnvironmentRoot(final String environmentName) {
        return m_bundlingRoot.resolve(environmentName);
    }
}