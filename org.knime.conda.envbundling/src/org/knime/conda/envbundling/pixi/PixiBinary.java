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
 *   Apr 28, 2025 (benjaminwilhelm): created
 */
package org.knime.conda.envbundling.pixi;

import java.io.IOException;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.knime.core.util.FileUtil;
import org.osgi.framework.FrameworkUtil;

/**
 * Utility to get the path to the pixi binary.
 *
 * @author Benjamin Wilhelm, KNIME GmbH, Berlin, Germany
 */
public final class PixiBinary {
    /*
    * TODO Cache path to binary (or initialize on class load)
    * TODO Improve exception handling
    */

    private PixiBinary() {
    }

    /**
     * @return the path to the pixi binary
     * @throws IllegalStateException if the pixi binary could not be located
     */
    public static String getPixiBinaryPath() {
        var bundle = FrameworkUtil.getBundle(PixiBinary.class);
        var fragments = Platform.getFragments(bundle);

        if (fragments.length < 1) {
            throw new IllegalStateException("Could not locate pixi binary because no pixi fragment is installed.");
        } else if (fragments.length > 1) {
            throw new IllegalStateException(
                "Could not locate pixi binary because multiple pixi fragment are installed.");
        }

        var url = FileLocator.find(fragments[0], relativePixiPath(), null);
        if (url == null) {
            throw new IllegalStateException("Could not locate pixi binary.");
        }

        try {
            var pixiFile = FileUtil.getFileFromURL(FileLocator.toFileURL(url));
            return pixiFile.getAbsolutePath();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not locate pixi binary because could not convert url to file url.",
                ex);
        }
    }

    private static Path relativePixiPath() {
        if (Platform.OS_WIN32.equals(Platform.getOS())) {
            return new Path("bin/pixi.exe");
        } else {
            return new Path("bin/pixi");
        }
    }
}
