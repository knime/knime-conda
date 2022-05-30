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
 *   May 30, 2022 (marcel): created
 */
package org.knime.conda;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.SystemUtils;

/**
 * @author Marcel Wiedenmann, KNIME GmbH, Konstanz, Germany
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 */
public final class CondaEnvironmentDirectory {

    private static final String PATH_ENVIRONMENT_VARIABLE_NAME = "PATH";

    private final String m_environmentDirectoryPath;

    /**
     * Note: "path" as in the PATH environment variable, not the path to the Conda environment or anything like that.
     * May be {@code null}.
     */
    private final String m_pathPrefix;

    /**
     * @param environmentDirectoryPath The path to the directory of the Conda environment.
     */
    public CondaEnvironmentDirectory(final String environmentDirectoryPath) {
        this(environmentDirectoryPath, null);
    }

    /**
     * @param environmentDirectoryPath The path to the directory of the Conda environment.
     * @param condaInstallationDirectoryPath The path to the base directory of the Conda installation associated with
     *            the Conda environment.
     */
    public CondaEnvironmentDirectory(final String environmentDirectoryPath,
        final String condaInstallationDirectoryPath) {
        m_environmentDirectoryPath = environmentDirectoryPath;
        if (SystemUtils.IS_OS_WINDOWS) {
            final List<String> pathPrefixes = new ArrayList<>();
            addToPrefixes(pathPrefixes, m_environmentDirectoryPath);
            addToPrefixes(pathPrefixes, m_environmentDirectoryPath, "Library", "mingw-w64", "bin");
            addToPrefixes(pathPrefixes, m_environmentDirectoryPath, "Library", "usr", "bin");
            addToPrefixes(pathPrefixes, m_environmentDirectoryPath, "Library", "bin");
            addToPrefixes(pathPrefixes, m_environmentDirectoryPath, "Scripts");
            addToPrefixes(pathPrefixes, m_environmentDirectoryPath, "bin");
            if (condaInstallationDirectoryPath != null) {
                // Note that we use the condabin directory of the given Conda installation directory regardless of
                // whether the given environment actually belongs to that Conda installation or a different instance.
                // This is a possible source of complications, but there is no known way to determine the environment's
                // "correct" Conda instance.
                addToPrefixes(pathPrefixes, condaInstallationDirectoryPath, "condabin");
            }
            m_pathPrefix = String.join(File.pathSeparator, pathPrefixes);
        } else {
            m_pathPrefix = null;
        }
    }

    private static void addToPrefixes(final List<String> prefixes, final String first, final String... more) {
        prefixes.add(Paths.get(first, more).toString());
    }

    /**
     * @return The path to the directory of the Conda environment.
     */
    public String getPath() {
        return m_environmentDirectoryPath;
    }

    /**
     * Returns the path to the Python executable of this environment.
     * <P>
     * Paths are determined as per https://docs.anaconda.com/anaconda/user-guide/tasks/integration/python-path/.
     *
     * @return The path to the Python executable of this environment.
     */
    public String getPythonExecutableString() {
        final Path executablePath;
        if (SystemUtils.IS_OS_LINUX || SystemUtils.IS_OS_MAC) {
            // No Sonar: Path is supposed to be user configurable.
            executablePath = Paths.get(m_environmentDirectoryPath, "bin", "python"); // NOSONAR
        } else if (SystemUtils.IS_OS_WINDOWS) {
            // No Sonar: Path is supposed to be user configurable.
            executablePath = Paths.get(m_environmentDirectoryPath, "python.exe"); // NOSONAR
        } else {
            throw Conda.createUnknownOSException();
        }
        return executablePath.toString();
    }

    /**
     * On Windows, calling this method prepends a number of library paths to the PATH entry of the given map of
     * environment variables. This may be required to resolve issues that occur when Python modules are searching for
     * DLLs located in a Conda environment that has not been activated prior to use.
     *
     * @param environment The environment variables to patch.
     */
    public void patchEnvironmentVariables(final Map<String, String> environment) {
        if (m_pathPrefix != null) {
            final String pathVariableName = findPathVariableName(environment);
            environment.merge(pathVariableName, m_pathPrefix,
                (oldPath, pathPrefix) -> pathPrefix + File.pathSeparatorChar + oldPath);
        }
    }

    /**
     * On Windows, the exact spelling/capitalization of the PATH variable name cannot be known in advance (and actually
     * should not matter since Windows is case-insensitive; Java does not seem to respect this fact, though). That is
     * why we have to scan the environment for the variable name and just pick whatever version of it is present.
     */
    private static String findPathVariableName(final Map<String, String> environment) {
        String pathVariableName = PATH_ENVIRONMENT_VARIABLE_NAME;
        if (SystemUtils.IS_OS_WINDOWS) {
            for (final String variableName : environment.keySet()) {
                if (PATH_ENVIRONMENT_VARIABLE_NAME.equalsIgnoreCase(variableName)) {
                    pathVariableName = variableName;
                    break;
                }
            }
        }
        return pathVariableName;
    }
}
