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
 *   Mar 21, 2022 (benjamin): created
 */
package org.knime.conda.envbundling;

import java.io.IOException;
import java.nio.file.Files;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.knime.core.util.FileUtil;
import org.osgi.framework.Bundle;

/**
 * Static utilities for Conda environment bundling.
 *
 * @author Benjamin Wilhelm, KNIME GmbH, Konstanz, Germany
 */
public final class CondaEnvironmentBundlingUtils {

    private CondaEnvironmentBundlingUtils() {
    }

    /**
     * Checks if the given path is a Conda environment by looking for the "conda-meta" directory.
     *
     * @param prefix the path to check
     * @return {@code true} if {@code prefix} looks like a conda environment.
     */
    public static boolean isCondaEnvironment(final java.nio.file.Path prefix) {
        return Files.isDirectory(prefix.resolve("conda-meta"));
    }

    /**
     * @return the platform identifier used by Conda to identify the current operating system and system architecture
     */
    public static String getCondaPlatformIdentifier() {
        var os = Platform.getOS();
        var arch = Platform.getOSArch();
        if (Platform.OS_LINUX.equals(os) && Platform.ARCH_X86_64.equals(arch)) {
            return "linux-64";
        }
        if (Platform.OS_WIN32.equals(os) && Platform.ARCH_X86_64.equals(arch)) {
            return "win-64";
        }
        if (Platform.OS_MACOSX.equals(os) && Platform.ARCH_X86_64.equals(arch)) {
            return "osx-64";
        }
        if (Platform.OS_MACOSX.equals(os) && Platform.ARCH_AARCH64.equals(arch)) {
            return "osx-arm64";
        }
        throw new IllegalStateException(
            String.format("The current platform is unknown (operating system: %s, system architecture: %s)", os, arch));
    }

    /**
     * Get the absolute, normalized, local path to the file in the specified bundle.
     *
     * @param bundle the bundle
     * @param path path relative to the bundle root
     * @return the absolute, normalized, local path to the file
     * @throws IOException if resolving the path failed
     */
    public static java.nio.file.Path getAbsolutePath(final Bundle bundle, final String path) throws IOException {
        return getAbsolutePath(bundle, new Path(path));
    }

    /**
     * Get the absolute, normalized, local path to the file in the specified bundle.
     *
     * @param bundle the bundle
     * @param path path relative to the bundle root
     * @return the absolute, normalized, local path to the file
     * @throws IOException if resolving the path failed
     */
    static java.nio.file.Path getAbsolutePath(final Bundle bundle, final Path path) throws IOException {
        final var url = FileLocator.find(bundle, path, null);
        if (url == null) {
            throw new IOException(
                "Could not find the file '" + path + "' in bundle '" + bundle.getSymbolicName() + "'.");
        }
        final var file = FileUtil.getFileFromURL(FileLocator.toFileURL(url));
        return file.toPath().normalize().toAbsolutePath();
    }
}
