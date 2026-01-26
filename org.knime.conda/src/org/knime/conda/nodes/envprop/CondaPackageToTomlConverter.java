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
 *   Jan 16, 2026 (created): created
 */
package org.knime.conda.nodes.envprop;

import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.knime.conda.CondaPackageSpec;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.io.IndentStyle;
import com.electronwill.nightconfig.toml.TomlWriter;

/**
 * Utility class for converting Conda package lists to Pixi TOML format.
 *
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 */
final class CondaPackageToTomlConverter {

    private CondaPackageToTomlConverter() {
        // Utility class
    }

    /**
     * Converts a list of Conda packages to Pixi TOML format.
     *
     * @param packages The list of included packages to convert
     * @param sourceOs The operating system where the environment was originally created (e.g., "linux", "mac",
     *            "windows")
     * @return A string containing the TOML representation of the packages
     */
    static String convertToPixiToml(final List<CondaPackageSpec> packages, final String sourceOs) {
        final Config config = Config.inMemory();

        // Collect all unique conda channels from packages
        final List<String> channels = packages.stream()
            .filter(pkg -> pkg.getName() != null && !pkg.getName().isBlank())
            .filter(pkg -> !"pypi".equals(pkg.getChannel()))
            .map(CondaPackageSpec::getChannel)
            .filter(channel -> channel != null && !channel.isBlank())
            .distinct()
            .toList();

        // [workspace] section
        final CommentedConfig workspace = CommentedConfig.inMemory();
        workspace.set("channels", channels.isEmpty() ? Arrays.asList("conda-forge") : channels);
        workspace.set("platforms", Arrays.asList("win-64", "linux-64", "osx-64", "osx-arm64"));
        workspace.setComment("platforms", "Originally created on " + formatOsName(sourceOs));
        config.set("workspace", workspace);

        // [dependencies] section - conda packages only
        final CommentedConfig dependencies = CommentedConfig.inMemory();
        boolean hasPypiPackages = false;
        for (final CondaPackageSpec pkg : packages) {
            if (pkg.getName() != null && !pkg.getName().isBlank()) {
                if ("pypi".equals(pkg.getChannel())) {
                    hasPypiPackages = true;
                } else {
                    // For conda packages, use inline table format: package = { version = "...", channel = "..." }
                    final Config packageSpec = Config.inMemory();
                    packageSpec.set("version", formatVersionConstraint(pkg));
                    if (pkg.getChannel() != null && !pkg.getChannel().isBlank()) {
                        packageSpec.set("channel", pkg.getChannel());
                    }
                    dependencies.set(pkg.getName(), packageSpec);
                }
            }
        }
        config.set("dependencies", dependencies);

        // [pypi-dependencies] section - pip packages only
        if (hasPypiPackages) {
            final CommentedConfig pypiDependencies = CommentedConfig.inMemory();
            for (final CondaPackageSpec pkg : packages) {
                if (pkg.getName() != null && !pkg.getName().isBlank() && "pypi".equals(pkg.getChannel())) {
                    pypiDependencies.set(pkg.getName(), formatVersionConstraint(pkg));
                }
            }
            config.set("pypi-dependencies", pypiDependencies);
        }

        // Write to string
        final StringWriter writer = new StringWriter();
        final TomlWriter tomlWriter = new TomlWriter();
        tomlWriter.setIndent(IndentStyle.SPACES_2);
        tomlWriter.write(config, writer);

        return writer.toString();
    }

    /**
     * Formats a version constraint for a package based on its version and build information.
     *
     * @param pkg The package specification
     * @return A version constraint string (e.g., "==1.2.3", ">=1.2.3", "*")
     */
    private static String formatVersionConstraint(final CondaPackageSpec pkg) {
        final String version = pkg.getVersion();
        if (version == null || version.isBlank() || "*".equals(version)) {
            return "*";
        }
        // Use exact version match to preserve the environment as closely as possible
        return "==" + version;
    }

    /**
     * Formats the OS identifier to a human-readable name.
     *
     * @param sourceOs The OS identifier (e.g., "linux", "mac", "windows")
     * @return A formatted OS name (e.g., "Linux", "macOS (Apple Silicon)", "Windows")
     */
    private static String formatOsName(final String sourceOs) {
        if (sourceOs == null || sourceOs.isBlank()) {
            return "unknown";
        }
        switch (sourceOs.toLowerCase(Locale.ROOT)) {
        case "linux":
            return "Linux";
        case "mac":
            return formatMacOsName();
        case "windows":
            return "Windows";
        default:
            return sourceOs;
        }
    }

    /**
     * Formats the macOS name with architecture information.
     *
     * @return A formatted macOS name with architecture (e.g., "macOS (Apple Silicon)" or "macOS (Intel)")
     */
    private static String formatMacOsName() {
        final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "macOS (Apple Silicon)";
        } else if (arch.contains("x86_64") || arch.contains("amd64")) {
            return "macOS (Intel)";
        } else {
            return "macOS";
        }
    }
}
