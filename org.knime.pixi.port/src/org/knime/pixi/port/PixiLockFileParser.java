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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

/**
 * Parses pixi.lock (v6) YAML files to extract per-platform package information.
 *
 * @author Marc Lehner, KNIME GmbH, Zurich, Switzerland
 */
final class PixiLockFileParser {

    private PixiLockFileParser() {
    }

    /**
     * Parse a pixi.lock v6 file and extract the packages for the default environment, grouped by platform.
     *
     * @param lockFileContent the raw YAML content of the pixi.lock file
     * @return a map from platform string (e.g. "win-64") to a sorted list of {@link PackageInfo} entries
     */
    @SuppressWarnings("unchecked")
    static Map<String, List<PackageInfo>> parsePackagesPerPlatform(final String lockFileContent) {
        if (lockFileContent == null || lockFileContent.isBlank()) {
            return Collections.emptyMap();
        }

        final var yaml = new Yaml();
        Map<String, Object> root = yaml.load(lockFileContent);
        if (root == null) {
            return Collections.emptyMap();
        }

        // Navigate to environments -> default -> packages
        var environments = (Map<String, Object>)root.get("environments");
        if (environments == null) {
            return Collections.emptyMap();
        }

        var defaultEnv = (Map<String, Object>)environments.get("default");
        if (defaultEnv == null) {
            return Collections.emptyMap();
        }

        var platformPackages = (Map<String, Object>)defaultEnv.get("packages");
        if (platformPackages == null) {
            return Collections.emptyMap();
        }

        // Build a lookup from URL -> package detail from the top-level "packages" section
        // to potentially get extra metadata in the future. Currently we parse name+version from the URL.
        var result = new LinkedHashMap<String, List<PackageInfo>>();

        for (var entry : platformPackages.entrySet()) {
            final String platform = entry.getKey();
            var packageList = (List<Object>)entry.getValue();
            if (packageList == null) {
                continue;
            }

            var packages = new ArrayList<PackageInfo>();
            for (Object pkgEntry : packageList) {
                if (pkgEntry instanceof Map<?, ?> pkgMap) {
                    parsePackageEntry(pkgMap, packages);
                }
            }
            packages.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
            result.put(platform, Collections.unmodifiableList(packages));
        }
        return Collections.unmodifiableMap(result);
    }

    private static void parsePackageEntry(final Map<?, ?> pkgMap, final List<PackageInfo> packages) {
        // Each entry is either { conda: URL } or { pypi: URL }
        for (String source : List.of("conda", "pypi")) {
            var url = pkgMap.get(source);
            if (url instanceof String urlStr) {
                var info = parsePackageFromUrl(urlStr, source);
                if (info != null) {
                    packages.add(info);
                }
                return;
            }
        }
    }

    /**
     * Parse package name and version from a conda/pypi URL.
     * <p>
     * Conda URLs have the form:
     * {@code https://conda.anaconda.org/conda-forge/linux-64/python-3.13.5-hec9711d_102_cp313.conda} where the
     * filename is {@code {name}-{version}-{build}.{ext}}. The name can contain hyphens (e.g.
     * {@code ca-certificates-2025.6.15-hbd8a1cb_0.conda}).
     * <p>
     * The strategy: split the filename (without extension) by hyphens, then find the split point where the second
     * segment starts with a digit — that's where the version begins. The rest before it is the name.
     *
     * @param url the full URL
     * @param source "conda" or "pypi"
     * @return a {@link PackageInfo} or {@code null} if parsing fails
     */
    static PackageInfo parsePackageFromUrl(final String url, final String source) {
        // Extract filename from URL
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == url.length() - 1) {
            return null;
        }
        String filename = url.substring(lastSlash + 1);

        // Remove extension (.conda, .tar.bz2, .whl, etc.)
        if (filename.endsWith(".tar.bz2")) {
            filename = filename.substring(0, filename.length() - ".tar.bz2".length());
        } else {
            int dot = filename.lastIndexOf('.');
            if (dot > 0) {
                filename = filename.substring(0, dot);
            }
        }

        // Split by hyphens and find where version starts (first segment starting with a digit)
        // The format is: {name}-{version}-{build_string}
        // We need to find the version segment and ignore the build string (last segment)
        String[] parts = filename.split("-");
        if (parts.length < 3) {
            // At minimum we need name-version-build
            return null;
        }

        // Find the first part (after index 0) that starts with a digit — that's the version start
        int versionIdx = -1;
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty() && Character.isDigit(parts[i].charAt(0))) {
                versionIdx = i;
                break;
            }
        }

        if (versionIdx < 1 || versionIdx >= parts.length - 1) {
            // No version found, or version is the last part (no build string)
            return null;
        }

        // Name = parts[0..versionIdx-1] joined by hyphens
        var nameBuilder = new StringBuilder(parts[0]);
        for (int i = 1; i < versionIdx; i++) {
            nameBuilder.append('-').append(parts[i]);
        }

        // Version = parts[versionIdx..length-2] joined by hyphens (last part is build string)
        var versionBuilder = new StringBuilder(parts[versionIdx]);
        for (int i = versionIdx + 1; i < parts.length - 1; i++) {
            versionBuilder.append('-').append(parts[i]);
        }

        return new PackageInfo(nameBuilder.toString(), versionBuilder.toString(), source);
    }
}
