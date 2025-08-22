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
import java.nio.file.Path;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Platform;
import org.eclipse.osgi.service.datalocation.Location;

/**
 * Provides the root directory where bundled Conda environments are stored.
 * <p>
 * Singleton with lazy initialization. Initialization errors are cached and re-thrown on subsequent calls to avoid
 * repeated work and noisy logs.
 *
 * @author Benjamin Wilhelm, KNIME GmbH, Berlin, Germany
 */
final class BundlingRoot {

    private static final String CONDA_ENVIRONMENTS_ROOT_NAME = "conda-environments";

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
     * @throws IOException if the configuration location is missing or cannot be converted to a file system path.
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
                var configLocation = Platform.getConfigurationLocation();
                if (configLocation == null) {
                    throw new IOException(
                        "A configuration location is required to store Conda environments but is not available. "
                            + "Start KNIME with a configuration location.");
                }

                Path root = configurationToPath(configLocation) // may throw IOException
                    .resolve(CONDA_ENVIRONMENTS_ROOT_NAME);

                inst = new BundlingRoot(root);
                INSTANCE = inst;
                return inst;

            } catch (IOException e) {
                INIT_FAILURE = e; // remember failure
                throw e;
            }
        }
    }

    /** Converts the Eclipse configuration location to a file-system path. */
    private static Path configurationToPath(final Location configLocation)
        throws IOException {
        // FileLocator ensures proper resolution of Eclipse platform URLs
        var url = FileLocator.toFileURL(configLocation.getURL());
        return new File(url.getPath()).toPath();
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
