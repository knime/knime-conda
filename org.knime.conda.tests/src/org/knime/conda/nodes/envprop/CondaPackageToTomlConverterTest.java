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
package org.knime.conda.nodes.envprop;

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.knime.conda.CondaPackageSpec;
import org.knime.conda.envinstall.pixi.PixiBinary;

/**
 * Tests for {@link CondaPackageToTomlConverter}.
 *
 * The tests validate generated {@code pixi.toml} files by invoking {@code pixi lock} using the Pixi binary bundled with
 * KNIME. When run via Maven/Tycho, executable file permissions may be missing, so the test setup ensures the binary is
 * executable.
 *
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 */
@SuppressWarnings({"javadoc", "restriction"})
public final class CondaPackageToTomlConverterTest {

    @BeforeClass
    public static void setupClass() throws Exception {
        var pathToPixi = Path.of(PixiBinary.getPixiBinaryPath());

        // Ensure that the Pixi binary is executable.
        //
        // In a real KNIME installation, the executable bit is set via a p2 touchpoint (see META-INF/p2.inf in the pixi
        // binary fragment).
        //
        // However, these tests are executed via Maven/Tycho, which materializes bundles directly into
        // target/work/plugins without running p2 provisioning steps. As a result, POSIX file permissions (including the
        // executable flag) may be lost, leading to "error=13, Permission denied" when attempting to run pixi.
        //
        // To make the tests robust and platform-independent, we explicitly ensure that the binary is executable here.
        if (Files.exists(pathToPixi) && !Files.isExecutable(pathToPixi)) {
            try {
                Files.setPosixFilePermissions(pathToPixi, PosixFilePermissions.fromString("rwxr-xr-x"));
            } catch (UnsupportedOperationException e) {
                // Fallback for non-POSIX file systems
                pathToPixi.toFile().setExecutable(true, false);
            }
        }
    }

    @Rule
    public TemporaryFolder m_tempFolder = new TemporaryFolder();

    /**
     * Tests conversion of conda packages only from a single channel.
     */
    @Test
    public void testCondaPackagesOnly() throws Exception {
        final List<CondaPackageSpec> packages = Arrays.asList( //
            new CondaPackageSpec("numpy", "1.24.3", "py311h08b1b3b_0", "conda-forge"), //
            new CondaPackageSpec("pandas", "2.0.1", "py311h320fe9a_0", "conda-forge"), //
            new CondaPackageSpec("python", "3.11.0", "h1fd4e5f_2", "conda-forge") //
        );

        validatePixiToml(packages, "linux");
    }

    /**
     * Tests conversion of conda packages from different channels.
     */
    @Test
    public void testMultipleChannels() throws Exception {
        final List<CondaPackageSpec> packages = Arrays.asList( //
            new CondaPackageSpec("numpy", "1.26.4", "py311h08b1b3b_0", "conda-forge"), //
            new CondaPackageSpec("knime-python-base", "5.9.0", "py311_0", "knime"), //
            new CondaPackageSpec("python", "3.11.0", "h1fd4e5f_2", "conda-forge") //
        );

        validatePixiToml(packages, "linux");
    }

    /**
     * Tests conversion of both conda and pip packages.
     */
    @Test
    public void testCondaAndPipPackages() throws Exception {
        final List<CondaPackageSpec> packages = Arrays.asList( //
            new CondaPackageSpec("numpy", "1.24.3", "py311h08b1b3b_0", "conda-forge"), //
            new CondaPackageSpec("pandas", "2.0.1", "py311h320fe9a_0", "conda-forge"), //
            new CondaPackageSpec("requests", "2.31.0", "", "pypi"), //
            new CondaPackageSpec("beautifulsoup4", "4.12.2", "", "pypi") //
        );

        validatePixiToml(packages, "mac");
    }

    /**
     * Tests conversion of packages with special characters in names (dots, dashes, underscores).
     */
    @Test
    public void testPackagesWithSpecialCharacters() throws Exception {
        final List<CondaPackageSpec> packages = Arrays.asList(
            // Conda package with dots
            new CondaPackageSpec("backports.functools_lru_cache", "2.0.0", "pyhd8ed1ab_0", "conda-forge"),
            // Conda package with dashes
            new CondaPackageSpec("scikit-learn", "1.2.2", "py311h320fe9a_0", "conda-forge"),
            // Conda package with underscores
            new CondaPackageSpec("python_abi", "3.11", "2_cp311", "conda-forge"),
            // Pip package with dots
            new CondaPackageSpec("backports.zoneinfo", "0.2.1", "", "pypi"),
            // Pip package with dashes
            new CondaPackageSpec("google-api-python-client", "2.88.0", "", "pypi"),
            // Pip package with underscores
            new CondaPackageSpec("importlib_metadata", "6.6.0", "", "pypi") //
        );

        validatePixiToml(packages, "windows");
    }

    /**
     * Tests conversion with a mix of all special cases: multiple channels, conda and pip, special characters.
     */
    @Test
    public void testMixedComplexCase() throws Exception {
        final List<CondaPackageSpec> packages = Arrays.asList(
            // Conda packages from conda-forge
            new CondaPackageSpec("numpy", "1.26.4", "py311h08b1b3b_0", "conda-forge"),
            new CondaPackageSpec("backports.functools_lru_cache", "2.0.0", "pyhd8ed1ab_0", "conda-forge"),
            new CondaPackageSpec("scikit-learn", "1.2.2", "py311h320fe9a_0", "conda-forge"),
            // Conda package from knime channel
            new CondaPackageSpec("knime-python-base", "5.9.0", "py311_0", "knime"),
            // Pip packages with various naming patterns
            new CondaPackageSpec("beautifulsoup4", "4.12.2", "", "pypi"), //
            new CondaPackageSpec("backports.zoneinfo", "0.2.1", "", "pypi"), //
            new CondaPackageSpec("google-api-python-client", "2.88.0", "", "pypi") //
        );

        validatePixiToml(packages, "mac");
    }

    /**
     * Helper method to validate that the generated TOML can be successfully locked by pixi.
     *
     * @param packages The packages to convert
     * @param sourceOs The source operating system
     * @throws Exception If validation fails
     */
    private void validatePixiToml(final List<CondaPackageSpec> packages, final String sourceOs) throws Exception {
        // Convert packages to TOML
        final String toml = CondaPackageToTomlConverter.convertToPixiToml(packages, sourceOs);

        // Validate using pixi lock (if available)
        validatePixiToml(toml);
    }

    /**
     * Helper method to validate a TOML string using pixi lock.
     *
     * @param toml the TOML content to validate
     * @throws Exception if validation fails or IO error occurs
     */
    private void validatePixiToml(final String toml) throws Exception {
        // Create temp directory and write pixi.toml
        final Path tempDir = m_tempFolder.newFolder().toPath();
        final Path tomlFile = tempDir.resolve("pixi.toml");
        Files.writeString(tomlFile, toml);

        // Verify the TOML file was created
        assertTrue("pixi.toml should exist", Files.exists(tomlFile));

        var callResult = PixiBinary.callPixi(tempDir, "lock");

        // Check that pixi lock succeeded
        assertTrue("pixi lock should succeed. Exit code: " + callResult.returnCode() + "\nstdout: "
            + callResult.stdout() + "\nstderr: " + callResult.stderr() + "\npixi lock: \n\n" + toml,
            callResult.isSuccess());

        // Verify that the lock file was created
        final Path lockFile = tempDir.resolve("pixi.lock");
        assertTrue("pixi.lock should be created", Files.exists(lockFile));
    }
}
