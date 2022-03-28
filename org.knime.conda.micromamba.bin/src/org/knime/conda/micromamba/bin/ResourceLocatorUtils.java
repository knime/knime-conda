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
 */
package org.knime.conda.micromamba.bin;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.core.runtime.FileLocator;
import org.osgi.framework.FrameworkUtil;

/**
 * Utilities to get the path for a resource inside a bundle
 *
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 */
final class ResourceLocatorUtils {
	private ResourceLocatorUtils() {
	}

	/**
	 * Resolves the location of the given resource inside the base directory of the given class's containing bundle.
	 * Returns the resolved absolute path.
	 *
	 * @param clazz The class within whose bundle the resource is located
	 * @param resourcePath The relative path of the source code directory.
	 * @return The absolute path to the resource
	 */
	public static Path getPathFor(final Class<?> clazz, final String resourcePath) {
		try {
			final var bundle = FrameworkUtil.getBundle(clazz);

			final var url = FileLocator
					.toFileURL(FileLocator.find(bundle, new org.eclipse.core.runtime.Path(resourcePath), null));

			try {
				return Paths.get(url.toURI());
			} catch (URISyntaxException e) {
				throw new IOException(e);
			}
		} catch (IOException ex) {
			throw new IllegalStateException(
					String.format("Failed to resolve resource '%s' of the bundle of class '%s'.", resourcePath, clazz),
					ex);
		}
	}
}
