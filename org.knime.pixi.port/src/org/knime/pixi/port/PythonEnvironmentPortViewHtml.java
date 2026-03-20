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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds a self-contained HTML page for the Python Environment port view. The page displays user-requested packages
 * with their locked versions, and an expandable list of all resolved packages per platform.
 *
 * @author Marc Lehner, KNIME GmbH, Zurich, Switzerland
 */
final class PythonEnvironmentPortViewHtml {

  private static final String TEMPLATE_HTML = loadResource("PythonEnvironmentPortViewTemplate.html");

  private static final String TEMPLATE_STYLE = loadResource("PythonEnvironmentPortViewStyle.css");

  private static final String TEMPLATE_SCRIPT = loadResource("PythonEnvironmentPortViewScript.js");

    private PythonEnvironmentPortViewHtml() {
    }

    /**
     * Build a complete HTML page for the port view.
     *
     * @param requestedDeps user-specified dependencies from pixi.toml (name → version constraint)
     * @param allPackages all resolved packages per platform from pixi.lock
     * @param defaultPlatform the platform to select by default in the dropdown
     * @return a self-contained HTML string
     */
    static String buildHtml(final Map<String, String> requestedDeps,
        final Map<String, List<PackageInfo>> allPackages, final String defaultPlatform) {
        return TEMPLATE_HTML.replace("__STYLE__", TEMPLATE_STYLE)
            .replace("__PLATFORM_OPTIONS__", platformOptionsHtml(allPackages, defaultPlatform))
            .replace("__REQUESTED_DEPS_JSON__", requestedDepsToJson(requestedDeps))
            .replace("__ALL_PACKAGES_JSON__", allPackagesToJson(allPackages))
            .replace("__DEFAULT_PLATFORM__", escapeJs(defaultPlatform))
            .replace("__SCRIPT__", TEMPLATE_SCRIPT);
    }

    private static String platformOptionsHtml(final Map<String, List<PackageInfo>> allPackages,
        final String defaultPlatform) {
        return allPackages.keySet().stream().map(platform -> {
            String selected = platform.equals(defaultPlatform) ? " selected" : "";
            return "    <option value=\"" + escapeHtml(platform) + "\"" + selected + ">" + escapeHtml(platform)
                + "</option>";
        }).collect(Collectors.joining("\n"));
    }

    private static String loadResource(final String name) {
        try (InputStream in = PythonEnvironmentPortViewHtml.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource for port view template: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load resource for port view template: " + name, e);
        }
    }

    private static String requestedDepsToJson(final Map<String, String> requestedDeps) {
        if (requestedDeps.isEmpty()) {
            return "{}";
        }
        return requestedDeps.entrySet().stream()
            .map(e -> "\"" + escapeJs(e.getKey()) + "\":\"" + escapeJs(e.getValue()) + "\"")
            .collect(Collectors.joining(",", "{", "}"));
    }

    private static String allPackagesToJson(final Map<String, List<PackageInfo>> allPackages) {
        if (allPackages.isEmpty()) {
            return "{}";
        }
        return allPackages.entrySet().stream().map(e -> {
            String platform = "\"" + escapeJs(e.getKey()) + "\"";
            String pkgArray = e.getValue().stream()
                .map(p -> "{\"name\":\"" + escapeJs(p.name()) + "\",\"version\":\"" + escapeJs(p.version())
                    + "\",\"source\":\"" + escapeJs(p.source()) + "\"}")
                .collect(Collectors.joining(",", "[", "]"));
            return platform + ":" + pkgArray;
        }).collect(Collectors.joining(",", "{", "}"));
    }

    private static String escapeHtml(final String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String escapeJs(final String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
