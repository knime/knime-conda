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

import java.io.StringWriter;
import java.util.Arrays;

import org.knime.pixi.nodes.PythonEnvironmentProviderNodeParameters.PackageSource;
import org.knime.pixi.nodes.PythonEnvironmentProviderNodeParameters.PackageSpec;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.io.IndentStyle;
import com.electronwill.nightconfig.toml.TomlWriter;

/**
 * Utility class for building pixi.toml manifest files from package specifications.
 *
 * @author Marc Lehner, KNIME GmbH, Konstanz, Germany
 * @since 5.11
 */
public final class PixiTomlBuilder {

    private PixiTomlBuilder() {
        // Utility class
    }

    /**
     * Build a pixi.toml manifest from an array of package specifications. Uses workspace structure with standard KNIME
     * channels and multi-platform support.
     *
     * @param packages the packages to include
     * @return the pixi.toml content as a string
     */
    public static String buildPixiTomlFromPackages(final PackageSpec[] packages) {
        Config config = Config.inMemory();

        // [workspace] section (required by pixi)
        CommentedConfig workspace = CommentedConfig.inMemory();
        workspace.set("channels", Arrays.asList("knime", "conda-forge"));
        workspace.set("platforms", Arrays.asList("win-64", "linux-64", "osx-64", "osx-arm64"));
        config.set("workspace", workspace);

        // [dependencies] section for conda packages
        CommentedConfig dependencies = CommentedConfig.inMemory();
        for (PackageSpec pkg : packages) {
            if (pkg.m_packageName == null || pkg.m_packageName.isBlank() || pkg.m_source == PackageSource.PIP) {
                continue;
            }
            dependencies.set(pkg.m_packageName, formatVersionConstraint(pkg));
        }
        config.set("dependencies", dependencies);

        // [pypi-dependencies] section for pip packages
        CommentedConfig pypiDependencies = CommentedConfig.inMemory();
        boolean hasPipPackages = false;
        for (PackageSpec pkg : packages) {
            if (pkg.m_packageName != null && !pkg.m_packageName.isBlank() && pkg.m_source == PackageSource.PIP) {
                pypiDependencies.set(pkg.m_packageName, formatVersionConstraint(pkg));
                hasPipPackages = true;
            }
        }
        if (hasPipPackages) {
            config.set("pypi-dependencies", pypiDependencies);
        }

        // Write to string
        StringWriter writer = new StringWriter();
        TomlWriter tomlWriter = new TomlWriter();
        tomlWriter.setIndent(IndentStyle.SPACES_2);
        tomlWriter.setWriteTableInlinePredicate(path -> false); // Never write tables inline
        tomlWriter.write(config, writer);
        return writer.toString();
    }

    /**
     * Format a version constraint string for a package specification.
     *
     * @param pkg the package specification
     * @return the formatted version constraint (e.g., ">=3.9,<=3.11" or "*")
     */
    static String formatVersionConstraint(final PackageSpec pkg) {
        boolean hasMin = pkg.m_minVersion != null && !pkg.m_minVersion.isBlank();
        boolean hasMax = pkg.m_maxVersion != null && !pkg.m_maxVersion.isBlank();

        if (hasMin && hasMax) {
            return ">=" + pkg.m_minVersion + ",<=" + pkg.m_maxVersion;
        } else if (hasMin) {
            return ">=" + pkg.m_minVersion;
        } else if (hasMax) {
            return "<=" + pkg.m_maxVersion;
        } else {
            return "*";
        }
    }
}
