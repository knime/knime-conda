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

import java.io.StringReader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;

/**
 * Extracts user-requested dependencies from a pixi.toml manifest.
 *
 * @author Marc Lehner, KNIME GmbH, Zurich, Switzerland
 */
final class PixiTomlDependencyExtractor {

    private PixiTomlDependencyExtractor() {
    }

    /**
     * Parse a pixi.toml string and extract the user-requested dependencies from the {@code [dependencies]} and
     * {@code [pypi-dependencies]} sections.
     *
     * @param tomlContent the raw pixi.toml content
     * @return a map from package name to version constraint string (e.g. "3.11.*", ">=1.0", or "*" for unconstrained)
     */
    static Map<String, String> extractRequestedDependencies(final String tomlContent) {
        if (tomlContent == null || tomlContent.isBlank()) {
            return Collections.emptyMap();
        }

        final var parser = new TomlParser();
        Config config;
        try {
            config = parser.parse(new StringReader(tomlContent));
        } catch (Exception e) { // NOSONAR — NightConfig can throw various unchecked exceptions
            return Collections.emptyMap();
        }

        var result = new LinkedHashMap<String, String>();

        extractSection(config, "dependencies", result);
        extractSection(config, "pypi-dependencies", result);

        return Collections.unmodifiableMap(result);
    }

    private static void extractSection(final Config config, final String sectionName,
        final Map<String, String> result) {
        Config section = config.get(sectionName);
        if (section == null) {
            return;
        }
        for (var entry : section.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            String versionConstraint = value != null ? value.toString() : "*";
            result.put(name, versionConstraint);
        }
    }
}
