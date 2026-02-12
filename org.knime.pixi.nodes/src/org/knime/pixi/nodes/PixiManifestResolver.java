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

import java.io.IOException;

import org.knime.core.node.NodeLogger;
import org.knime.pixi.nodes.PythonEnvironmentProviderNodeParameters.MainInputSource;
import org.knime.pixi.nodes.PythonEnvironmentProviderNodeParameters.PackageSpec;

/**
 * Utility class for resolving pixi.toml manifest content from various input sources.
 *
 * @author Marc Lehner, KNIME GmbH, Konstanz, Germany
 * @since 5.11
 */
final class PixiManifestResolver {

    private PixiManifestResolver() {
        // Utility class
    }

    /**
     * Retrieves TOML manifest content from various input sources. Throws exceptions for all error cases to provide
     * clear error messages.
     */
    static String getTomlContent(final MainInputSource inputSource, final PackageSpec[] packages,
        final String tomlContent, final String yamlContent, final NodeLogger logger) throws IOException {
        logger.debug("Getting TOML content for: " + inputSource);
        switch (inputSource) {
            case SIMPLE:
                logger.debug("Building TOML from " + packages.length + " packages");
                String result = PixiTomlBuilder.buildPixiTomlFromPackages(packages);
                logger.debug("Generated TOML (" + result.length() + " chars)");
                return result;
            case TOML_EDITOR:
                logger.debug("Using TOML from editor (" + tomlContent.length() + " chars)");
                return tomlContent;
            case YAML_EDITOR:
                // TODO in case of the YAML editor, we should save the TOML as part of the resolved environment
                // Right now, we only save the lockfile but re-generate the TOML access of the TOML file
                logger.debug("Converting YAML from editor to TOML");
                try {
                    String toml = PixiYamlImporter.convertYamlToToml(yamlContent);
                    logger.debug("Converted TOML (" + toml.length() + " chars)");
                    return toml;
                } catch (Exception e) {
                    throw new IOException("Failed to convert YAML to TOML: " + e.getMessage(), e);
                }
            default:
                logger.error("Unknown input source: " + inputSource);
                throw new IOException("Unknown input source: " + inputSource);
        }
    }
}
