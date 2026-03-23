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
 *   Mar 20, 2026 (Marc Lehner): created
 */
package org.knime.pixi.port;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.knime.core.webui.data.InitialDataService;
import org.knime.core.webui.data.RpcDataService;
import org.knime.core.webui.node.port.PortView;
import org.knime.core.webui.node.port.PortViewFactory;
import org.knime.core.webui.node.port.PortViewManager;
import org.knime.core.webui.node.port.PortViewManager.PortViewDescriptor;
import org.knime.core.webui.page.Page;

/**
 * Factory that creates a webui-based port view for {@link PythonEnvironmentPortObject}. The view displays the
 * user-requested packages with their locked versions and an expandable list of all resolved packages, with a platform
 * selector.
 *
 * @author Marc Lehner, KNIME GmbH, Zurich, Switzerland
 */
public final class PythonEnvironmentPortViewFactory implements PortViewFactory<PythonEnvironmentPortObject> {

    static {
        PortViewManager.registerPortViews(PythonEnvironmentPortObject.TYPE,
            List.of(new PortViewDescriptor("Python Environment", new PythonEnvironmentPortViewFactory())),
            List.of(), // no views when only configured (need executed port object data)
            List.of(0) // view at index 0 available when executed
        );
    }

    /**
     * Forces the static initializer to run, ensuring the port view is registered. This method should be called from a
     * class that is guaranteed to be loaded (e.g., the port object class itself or a bundle activator).
     */
    public static void ensureRegistered() {
        // Static initializer has already run by the time this method is called.
    }

    @Override
    public PortView createPortView(final PythonEnvironmentPortObject portObject) {
        // Parse the TOML and lock file contents
        final Map<String, String> requestedDeps =
            PixiTomlDependencyExtractor.extractRequestedDependencies(portObject.getPixiTomlContent());
        final Map<String, List<PackageInfo>> allPackages =
            PixiLockFileParser.parsePackagesPerPlatform(portObject.getPixiLockContent());

        final String defaultPlatform = detectCurrentPlatform(allPackages);
        final String html = PythonEnvironmentPortViewHtml.buildHtml(requestedDeps, allPackages, defaultPlatform);

        return new PortView() {

            @Override
            public Page getPage() {
                return Page.create() //
                    .fromString(() -> html) //
                    .relativePath("index.html");
            }

            @Override
            public Optional<InitialDataService<?>> createInitialDataService() {
                return Optional.empty();
            }

            @Override
            public Optional<RpcDataService> createRpcDataService() {
                return Optional.empty();
            }
        };
    }

    /**
     * Detect the current platform and map it to a pixi platform string.
     *
     * @param availablePlatforms the platforms available in the lock file
     * @return the detected platform string, or the first available platform if detection fails
     */
    private static String detectCurrentPlatform(final Map<String, List<PackageInfo>> availablePlatforms) {
        final String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        String platform;
        if (osName.contains("win")) {
            platform = "win-64";
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            platform = osArch.contains("aarch64") || osArch.contains("arm") ? "osx-arm64" : "osx-64";
        } else {
            // Default to linux
            platform = "linux-64";
        }

        if (availablePlatforms.containsKey(platform)) {
            return platform;
        }

        // Fall back to the first available platform
        return availablePlatforms.keySet().stream().findFirst().orElse(platform);
    }
}
