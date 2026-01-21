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
 *   Jan 20, 2026 (Marc Lehner): created
 */
package org.knime.pixi.nodes;

import java.nio.file.Path;

import org.eclipse.core.runtime.Platform;

/**
 * Utility class for resolving bundled Pixi environment paths.
 *
 * @author Marc Lehner, KNIME GmbH, Konstanz, Germany
 * @since 5.10
 */
final class PixiBundlingUtils {

    private PixiBundlingUtils() {
        // Utility class
    }

    /**
     * Get the bundling root path. Checks for KNIME_PYTHON_BUNDLING_PATH environment variable first, otherwise returns
     * {installation_root}/bundling. This logic needs to be kept in sync with the one used in the
     * CondaEnvironmentRegistry.
     *
     * @return the bundling root path
     * @throws Exception if unable to resolve the path
     */
    public static Path getBundlingRootPath() throws Exception {
        // Check for KNIME_PYTHON_BUNDLING_PATH environment variable
        var bundlingPathFromVar = System.getenv("KNIME_PYTHON_BUNDLING_PATH");
        if (bundlingPathFromVar != null && !bundlingPathFromVar.isBlank()) {
            return Path.of(bundlingPathFromVar);
        }

        // Otherwise use installation_root/bundling
        Path installationRoot = getInstallationRoot();
        return installationRoot.resolve("bundling");
    }

    /**
     * Get the KNIME installation root directory by resolving from the bundle location.
     *
     * @return the installation root path
     * @throws Exception if unable to resolve the path
     */
    static Path getInstallationRoot() throws Exception {
        var bundle = Platform.getBundle("org.knime.pixi.nodes");
        String bundleLocationString =
            org.eclipse.core.runtime.FileLocator.getBundleFileLocation(bundle).orElseThrow().getAbsolutePath();
        Path bundleLocationPath = Path.of(bundleLocationString);
        return bundleLocationPath.getParent().getParent();
    }
}
