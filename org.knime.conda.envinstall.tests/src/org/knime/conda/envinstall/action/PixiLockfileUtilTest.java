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
 *   Jun 17, 2025 (benjaminwilhelm): created
 */
package org.knime.conda.envinstall.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for PixiLockfileUtil.
 */
class PixiLockfileUtilTest {

    @TempDir
    Path m_tempDir; // fresh temp directory for every test

    // --------------------------------------------------------------------
    //  makeURLsLocal – failure path
    // --------------------------------------------------------------------
    @Test
    void makeURLsLocal_throwsOnWrongVersion() {
        Map<String, Object> lockfile = new HashMap<>();
        lockfile.put("version", 5); // anything ≠ 6
        lockfile.put("environments", Map.of());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> PixiLockfileUtil.makeURLsLocal(lockfile, "dummy", m_tempDir),
            "Expected IllegalArgumentException for wrong version");
        assertTrue(ex.getMessage().contains("version 6"), "Exception message should mention 'version 6'");
    }

    // --------------------------------------------------------------------
    //  makeURLsLocal – happy path incl. every transformation
    // --------------------------------------------------------------------
    @Test
    void makeURLsLocal_rewritesEverythingCorrectly() throws Exception {
        /* ---------- build a minimal but complete lockfile ---------- */
        String envName = "test-env";

        // sample package URLs
        String condaUrl = "https://conda.anaconda.org/conda-forge/linux-64/libarrow-20.0.0-h314c690_7_cpu.conda";
        String pypiUrl = "https://files.pythonhosted.org/packages/xx/package-1.0.0.tar.gz";

        // environment-level package refs
        Map<String, List<Map<String, String>>> packagesByOs = new HashMap<>();
        packagesByOs.put("linux-64",
            List.of(new HashMap<>(Map.of("conda", condaUrl)), new HashMap<>(Map.of("pypi", pypiUrl))));

        Map<String, Object> envDef = new HashMap<>();
        envDef.put("channels", List.of(Map.of("url", "https://conda.anaconda.org/conda-forge")));
        envDef.put("indexes", List.of("https://pypi.org/simple"));
        envDef.put("find-links", List.of("https://pypi.org/simple"));
        envDef.put("packages", packagesByOs);

        Map<String, Map<String, Object>> envs = new HashMap<>();
        envs.put(envName, envDef);
        envs.put("other-env", new HashMap<>()); // will be removed

        // full package list (lockfile-level)
        Map<String, String> fullConda =
            new HashMap<>(Map.of("name", "libarrow", "conda", condaUrl, "channel", "conda-forge"));
        Map<String, String> fullPypi = new HashMap<>(Map.of("name", "package", "pypi", pypiUrl));

        Map<String, Object> lockfile = new HashMap<>();
        lockfile.put("version", 6);
        lockfile.put("environments", envs);
        lockfile.put("packages", List.of(fullConda, fullPypi));

        /* ---------- run subject under test ---------- */
        Path resources = Files.createDirectories(m_tempDir.resolve("resources"));
        PixiLockfileUtil.makeURLsLocal(lockfile, envName, resources);

        /* ---------- verify global effects ---------- */
        // only chosen environment remains
        assertEquals(Set.of(envName), ((Map<?, ?>)lockfile.get("environments")).keySet(),
            "irrelevant environments should be removed");

        Path channelPath = resources.resolve("channel");
        Path pypiPath = resources.resolve("pypi");

        /* ---------- environment-level assertions ---------- */
        @SuppressWarnings("unchecked")
        Map<String, Object> postEnvDef = ((Map<String, Map<String, Object>>)lockfile.get("environments")).get(envName);

        // channel list replaced with single file:// URL
        assertEquals(List.of(Map.of("url", channelPath.toUri().toURL().toString())), postEnvDef.get("channels"),
            "channels should be replaced with local file URL");
        // pypi index links removed
        assertFalse(postEnvDef.containsKey("indexes"), "indexes should be removed");
        assertFalse(postEnvDef.containsKey("find-links"), "find-links should be removed");

        // env packages rewritten to local paths
        @SuppressWarnings("unchecked")
        List<Map<String, String>> linuxPkgs =
            ((Map<String, List<Map<String, String>>>)postEnvDef.get("packages")).get("linux-64");

        assertEquals(channelPath.resolve("linux-64/libarrow-20.0.0-h314c690_7_cpu.conda").toString(),
            linuxPkgs.get(0).get("conda"), "conda package path should be rewritten to local");
        assertEquals(pypiPath.resolve("package-1.0.0.tar.gz").toString(), linuxPkgs.get(1).get("pypi"),
            "pypi package path should be rewritten to local");

        /* ---------- full package list assertions ---------- */
        assertEquals(channelPath.resolve("linux-64/libarrow-20.0.0-h314c690_7_cpu.conda").toString(),
            fullConda.get("conda"), "fullConda conda path should be rewritten to local");
        assertEquals("linux-64", fullConda.get("subdir"), "subdir must be added for conda packages");
        assertEquals(channelPath.toUri().toURL().toString(), fullConda.get("channel"),
            "channel should be replaced with local file URL");

        assertEquals(pypiPath.resolve("package-1.0.0.tar.gz").toString(), fullPypi.get("pypi"),
            "fullPypi pypi path should be rewritten to local");
    }

    // --------------------------------------------------------------------
    //  readLockfile / writeLockfile round-trip
    // --------------------------------------------------------------------
    @Test
    void readWrite_roundTripPreservesContent() throws Exception {
        Map<String, Object> original = Map.of("version", 6, "foo", List.of(1, 2, 3));

        Path file = m_tempDir.resolve("pixi.lock");
        PixiLockfileUtil.writeLockfile(original, file);
        Map<String, Object> reloaded = PixiLockfileUtil.readLockfile(file);

        assertEquals(original, reloaded, "YAML write → read must be loss-less");
    }
}
