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
 *   Mar 20, 2026 (created): created
 */
package org.knime.pixi.nodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.knime.conda.envinstall.pixi.PixiBinary;
import org.knime.conda.envinstall.pixi.PixiBinary.PixiBinaryLocationException;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;

/**
 * Tests for {@link PixiYamlImporter}.
 */
@SuppressWarnings({"javadoc", "restriction"})
final class PixiYamlImporterTest {

    private static boolean s_isPixiAvailable;

    @BeforeAll
    static void setupClass() throws Exception {
        final Path pathToPixi;
        try {
            pathToPixi = Path.of(PixiBinary.getPixiBinaryPath());
            s_isPixiAvailable = true;
        } catch (PixiBinaryLocationException ex) {
            s_isPixiAvailable = false;
            return;
        }

        if (Files.exists(pathToPixi) && !Files.isExecutable(pathToPixi)) {
            try {
                Files.setPosixFilePermissions(pathToPixi, PosixFilePermissions.fromString("rwxr-xr-x"));
            } catch (UnsupportedOperationException e) {
                pathToPixi.toFile().setExecutable(true, false);
            }
        }
    }

    @Test
    void convertsStandardCondaYamlToToml() {
        final String yaml = """
            name: pyenv
            channels:
              - knime
              - conda-forge
            dependencies:
              - python=3.11
              - pandas>=2.0,<3
              - conda-forge::numpy=1.26
              - pip:
                  - requests==2.31.0
                  - backports.zoneinfo==0.2.1
            """;

        final String toml = PixiYamlImporter.convertYamlToToml(yaml);

        final Config parsedToml = new TomlParser().parse(toml);
        final Config workspace = parsedToml.get("workspace");
        assertNotNull(workspace);
        assertEquals(List.of("knime", "conda-forge"), workspace.get("channels"));
        assertEquals(Set.of("win-64", "linux-64", "osx-64", "osx-arm64"), Set.copyOf(workspace.get("platforms")));

        final Config dependencies = parsedToml.get("dependencies");
        assertNotNull(dependencies);
        assertEquals("3.11.*", dependencies.get("python"));
        assertEquals(">=2.0,<3", dependencies.get("pandas"));

        final Object numpyObj = dependencies.get("numpy");
        final Config numpyConfig = assertInstanceOf(Config.class, numpyObj);
        assertEquals("1.26.*", numpyConfig.get("version"));
        assertEquals("conda-forge", numpyConfig.get("channel"));

        final Config pypiDependencies = parsedToml.get("pypi-dependencies");
        assertNotNull(pypiDependencies);
        assertEquals("==2.31.0", pypiDependencies.get("requests"));

        Object zoneInfoConstraint = pypiDependencies.get("backports.zoneinfo");
        if (zoneInfoConstraint == null) {
            final Object backportsObj = pypiDependencies.get("backports");
            if (backportsObj instanceof Config backportsConfig) {
                zoneInfoConstraint = backportsConfig.get("zoneinfo");
            }
        }
        assertEquals("==0.2.1", zoneInfoConstraint);
    }

    @Test
    void rejectsUnsupportedDependencyMap() {
        final String yaml = """
            dependencies:
              - python=3.11
              - unsupported:
                  - value
            """;

        assertThrows(RuntimeException.class, () -> PixiYamlImporter.convertYamlToToml(yaml));
    }

    @Test
    void rejectsEmptyContent() {
        assertThrows(RuntimeException.class, () -> PixiYamlImporter.convertYamlToToml("  \n"));
    }

    @Test
    void generatedTomlCanBeLockedByPixi() throws Exception {
        Assumptions.assumeTrue(s_isPixiAvailable, "Skipping pixi lock smoke test because pixi binary is unavailable");

        final String yaml = """
            channels:
              - conda-forge
            dependencies:
              - python=3.11
            """;

        final String toml = PixiYamlImporter.convertYamlToToml(yaml);
        final Path tempDir = Files.createTempDirectory("pixi-yaml-importer-test-");
        try {
            Files.writeString(tempDir.resolve("pixi.toml"), toml);

            var callResult = PixiBinary.callPixi(tempDir, "lock");
            assertTrue(callResult.isSuccess(), "pixi lock should succeed. Exit code: " + callResult.returnCode()
                + "\nstdout: " + callResult.stdout() + "\nstderr: " + callResult.stderr() + "\npixi.toml:\n" + toml);
            assertTrue(Files.exists(tempDir.resolve("pixi.lock")), "pixi.lock should be created");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void deleteRecursively(final Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (var files = Files.walk(dir)) {
            files.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }
}
