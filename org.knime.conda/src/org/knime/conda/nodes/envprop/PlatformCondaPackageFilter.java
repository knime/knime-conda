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
 *   May 27, 2021 (marcel): created
 */
package org.knime.conda.nodes.envprop;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Set;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.knime.core.node.NodeLogger;
import org.knime.core.util.FileUtil;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingJsonFactory;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * @noreference Only exposed for cross-plugin testing purposes.
 * @author Marcel Wiedenmann, KNIME GmbH, Konstanz, Germany
 */
@SuppressWarnings("javadoc") // This class should be treated as package-private, the corresponding javadoc rules apply.
public final class PlatformCondaPackageFilter {

    private static final String OS_LINUX = "Linux";

    private static final String OS_MAC = "MacOS";

    private static final String OS_WINDOWS = "Windows";

    private static final String THIS_PLUGIN_ID = "org.knime.python2.nodes";

    private static final String FILTER_DIRECTORY_NAME = "conda";

    private static final String FILTER_FILE_NAME = "package_filter.json";

    public static PlatformCondaPackageFilter createLinuxFilterList() throws IOException {
        return new PlatformCondaPackageFilter(OS_LINUX);
    }

    public static PlatformCondaPackageFilter createMacFilterList() throws IOException {
        return new PlatformCondaPackageFilter(OS_MAC);
    }

    public static PlatformCondaPackageFilter createWindowsFilterList() throws IOException {
        return new PlatformCondaPackageFilter(OS_WINDOWS);
    }

    private final Set<String> m_excludedPackages = new HashSet<>();

    private PlatformCondaPackageFilter(final String targetOs) throws IOException {
        final File filterFile = getFilterFile();
        try (final JsonParser parser = new MappingJsonFactory().createParser(filterFile)) {
            final ObjectNode root = parser.readValueAsTree();
            populateExcludedPackages(root.fields(), targetOs);
        }
    }

    private void populateExcludedPackages(final Iterator<Entry<String, JsonNode>> fields, final String targetOs) {
        while (fields.hasNext()) {
            final Entry<String, JsonNode> field = fields.next();
            final ArrayNode compatibleOss = ((ArrayNode)field.getValue());
            boolean compatibleWithTargetOs = false;
            for (final JsonNode os : compatibleOss) {
                if (os.textValue().equalsIgnoreCase(targetOs)) {
                    compatibleWithTargetOs = true;
                    break;
                }
            }
            if (!compatibleWithTargetOs) {
                final String packageName = field.getKey();
                m_excludedPackages.add(packageName);
            }
        }
    }

    public boolean excludesPackage(final String packageName) {
        return m_excludedPackages.contains(packageName);
    }

    private static File getFilterFile() {
        try {
            final var bundle = Platform.getBundle(THIS_PLUGIN_ID);
            final var url =
                FileLocator.find(bundle, new Path(Paths.get(FILTER_DIRECTORY_NAME, FILTER_FILE_NAME).toString()), null);
            return url != null ? FileUtil.getFileFromURL(FileLocator.toFileURL(url)) : null;
        } catch (final Exception e) {
            NodeLogger.getLogger(PlatformCondaPackageFilter.class).debug(e.getMessage(), e);
            return null;
        }
    }
}
