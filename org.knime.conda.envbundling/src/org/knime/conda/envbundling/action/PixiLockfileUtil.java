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
 *   Jun 16, 2025 (benjaminwilhelm): created
 */
package org.knime.conda.envbundling.action;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

/**
 * Utilities to read and write Pixi lockfiles and to convert URLs in the lockfile to local file URLs/Paths.
 *
 * @author Marc Lehner, KNIME AG, Zurich, Switzerland
 * @author Benjamin Wilhelm, KNIME GmbH, Berlin, Germany
 */
final class PixiLockfileUtil {

    private static final Yaml YAML = new Yaml();

    private static final List<String> CONDA_OS_NAMES = List.of("linux-64", "osx-64", "osx-arm64", "win-64");

    private PixiLockfileUtil() {
    }

    /**
     * Reads a Pixi lockfile from the given path.
     *
     * @param lockfilePath the path to the lockfile
     * @return the parsed {@link PixiLockfile}
     * @throws IOException if an I/O error occurs while reading the file
     */
    static Map<String, Object> readLockfile(final Path lockfilePath) throws IOException {
        try (var inStream = Files.newInputStream(lockfilePath)) {
            return YAML.load(inStream);
        }
    }

    static void writeLockfile(final Map<String, Object> lockfile, final Path lockfilePath) throws IOException {
        try (var outWriter = Files.newBufferedWriter(lockfilePath)) {
            YAML.dump(lockfile, outWriter);
        }
    }

    /**
     * Modifies the Pixi lockfile inplace to replace all URLs with local file paths.
     *
     * <ul>
     * <li>Removes all environments but the one specified by the environmentName</li>
     * <li>Replaces all channel URLs with a local file URL pointing to the conda channel path</li>
     * <li>Removes links to PyPI (like https://pypi.org/simple) to prevent calls to the PyPI index</li>
     * <li>Replaces all package paths in the environment definition with local file paths</li>
     * <li>Replaces all package paths in the full packages list with local file paths</li>
     * </ul>
     *
     * @param lockfile the Pixi lockfile to modify
     * @throws MalformedURLException if a URL cannot be constructed from the local path
     */
    static void makeURLsLocal(final Map<String, Object> lockfile, final String environmentName,
        final Path envResourcesFolder) throws MalformedURLException {
        // This mapping was implemented for Pixi lockfile version 6, so we check the version
        if (lockfile.get("version") == null || !lockfile.get("version").equals(6)) {
            throw new IllegalArgumentException(
                "The Pixi lockfile must be version 6, but is: " + lockfile.get("version"));
        }

        // Remove all environments but the one specified by the environmentName
        var environments = getAllEnvironments(lockfile);
        for (var envName : List.copyOf(environments.keySet())) {
            if (!envName.equals(environmentName)) {
                environments.remove(envName);
            }
        }

        // Useful paths
        var condaChannelPath = envResourcesFolder.resolve("channel");
        var pypiIndexPath = envResourcesFolder.resolve("pypi");

        // The environment definition for the specified environment
        var environmentDef = getEnvironment(lockfile, environmentName);

        makeChannelUrlsLocal(environmentDef, condaChannelPath);
        removePypiIndexUrls(environmentDef);
        makeEnvironmentPackagePathsLocal(environmentDef, condaChannelPath, pypiIndexPath);
        makeFullPackagePathsLocal(lockfile, condaChannelPath, pypiIndexPath);
    }

    /** Replaces all channel URLs by a local file URL pointing to the conda channel path. */
    private static void makeChannelUrlsLocal(final Map<String, Object> environmentDef, final Path condaChannelPath)
        throws MalformedURLException {
        environmentDef.put("channels", List.of(Map.of("url", condaChannelPath.toUri().toURL().toString())));
    }

    /** Removes links to PyPI (like https://pypi.org/simple) to prevent calls to the PyPI index. */
    private static void removePypiIndexUrls(final Map<String, Object> environmentDef) {
        environmentDef.remove("indexes");
        environmentDef.remove("find-links");
    }

    /** Replaces all package paths in the environment definition with local file paths. */
    private static void makeEnvironmentPackagePathsLocal(final Map<String, Object> environmentDef,
        final Path condaChannelPath, final Path pypiIndexPath) {
        @SuppressWarnings("unchecked")
        var packagesField = (Map<String, List<Map<String, String>>>)environmentDef.get("packages");
        for (var osName : CONDA_OS_NAMES) {
            var packages = packagesField.get(osName);
            if (packages != null) {
                for (var packageRef : packages) {
                    packageRef.computeIfPresent("conda", (key, name) -> swapToLocalPathConda(condaChannelPath, name));
                    packageRef.computeIfPresent("pypi", (key, name) -> swapToLocalPathPypi(pypiIndexPath, name));
                }
            }
        }
    }

    private static void makeFullPackagePathsLocal(final Map<String, Object> lockfile, final Path condaChannelPath,
        final Path pypiIndexPath) throws MalformedURLException {
        var condaChannelUrl = condaChannelPath.toUri().toURL().toString();
        @SuppressWarnings("unchecked")
        var fullPackagesList = (List<Map<String, String>>)lockfile.get("packages");
        for (var packageEntry : fullPackagesList) {
            if (packageEntry.containsKey("conda")) {
                var packageFileUrl = packageEntry.get("conda");
                packageEntry.put("conda", swapToLocalPathConda(condaChannelPath, packageFileUrl));
                // For conda packages, we need to add the subdir field
                packageEntry.put("subdir", getPackageSubDir(packageFileUrl));

            }

            // Replace the PyPI package URL with a local file path
            packageEntry.computeIfPresent("pypi", (key, name) -> swapToLocalPathPypi(pypiIndexPath, name));

            // Remove the channel field if it exists, as it is not needed in the Pixi lockfile
            packageEntry.put("channel", condaChannelUrl);
        }
    }

    /**
     * Replace a name of a PyPI package in the form of a URL to the PyPI index with a local file path.
     *
     * @param pypiPath the path to the local PyPI index
     * @param packageFileUrl the URL of the PyPI package, e.g.
     *            https://files.pythonhosted.org/packages/3f/0c/4b1d8e2f5a6b7c9e1f8[..]/package-1.0.0.tar.gz
     * @return the local file path to the PyPI package, e.g. /path/to/pypi/index/package-1.0.0.tar.gz
     */
    private static String swapToLocalPathPypi(final Path pypiPath, final String packageFileUrl) {
        var fileName = packageFileUrl.substring(packageFileUrl.lastIndexOf('/') + 1);
        return pypiPath.resolve(fileName).toString();
    }

    /**
     * Replace a name of a conda package in the form of a URL to a Conda channel with a local file path.
     *
     * @param condaChannelPath the path to the local conda channel
     * @param packageFileUrl the URL of the conda package, e.g.
     *            https://conda.anaconda.org/conda-forge/linux-64/libarrow-20.0.0-h314c690_7_cpu.conda
     * @return the local file path to the conda package, e.g.
     *         /path/to/conda/channel/linux-64/libarrow-20.0.0-h314c690_7_cpu.conda
     */
    private static String swapToLocalPathConda(final Path condaChannelPath, final String packageFileUrl) {
        var indexBeforeSubdirAndName = packageFileUrl.lastIndexOf('/', packageFileUrl.lastIndexOf('/') - 1);
        var subDirAndName = packageFileUrl.substring(indexBeforeSubdirAndName + 1); // e.g. linux-64/libarrow-[..].conda
        return condaChannelPath.resolve(subDirAndName).toString();
    }

    /**
     * Extracts the subdirectory name from a conda package name.
     *
     * @param packageFileUrl the URL of the conda package, e.g.
     *            https://conda.anaconda.org/conda-forge/linux-64/libarrow-20.0.0-h314c690_7_cpu.conda
     * @return the subdirectory name, e.g. "linux-64" or "noarch"
     */
    private static String getPackageSubDir(final String packageFileUrl) {
        var lastSlashIndex = packageFileUrl.lastIndexOf('/');
        return packageFileUrl.substring(packageFileUrl.lastIndexOf('/', lastSlashIndex - 1) + 1, lastSlashIndex);
    }

    // Utilites for accessing fields in the lockfile

    private static Map<String, Object> getEnvironment(final Map<String, Object> lockfile,
        final String environmentName) {
        var environments = getAllEnvironments(lockfile);
        return environments.get(environmentName);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> getAllEnvironments(final Map<String, Object> lockfile) {
        return (Map<String, Map<String, Object>>)lockfile.get("environments");
    }
}
