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
 *   Jan 7, 2026 (Marc Lehner): created
 */
package org.knime.pixi.port;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import org.knime.conda.prefs.CondaPreferences;
import org.knime.core.node.NodeLogger;

/**
 * Utility class to manage the mapping between Pixi lock file content and environment directories. It uses a hash of the
 * lock file content to determine the environment directory, and handles potential hash collisions by appending numeric
 * suffixes to the directory name. The mapping is stored in a text file within the base Pixi environment directory,
 * allowing for consistent resolution of environment directories.
 *
 * @author Marc Lehner
 * @since 5.11
 */
final class PixiEnvMapping {

    /** Arbitrary limit to prevent infinite loops. We should never have more than a few collisions in practice. */
    private static final int MAX_HASH_ENV_COLLISION_SUFFIX = 10;

    private PixiEnvMapping() {
    }

    private static final NodeLogger LOGGER = NodeLogger.getLogger(PixiEnvMapping.class);

    private static final String ENV_MAPPING_FILE = "knime_env_mapping.txt";

    /**
     * Resolves the Pixi environment directory based on the provided lock file content. It uses a mapping file to store
     * the association between lock file hashes and environment directories. In case of hash collisions, it appends
     * numeric suffixes to find a unique directory.
     *
     * @param lockFileContent the content of the pixi.lock file to hash and use for mapping
     * @return the path to the resolved Pixi environment directory
     * @throws IOException if an I/O error occurs while reading or writing the mapping file, or while creating
     *             directories
     */
    static synchronized Path resolvePixiEnvDirectory(final String lockFileContent) throws IOException {
        final Path base = CondaPreferences.getPixiEnvPath();

        Map<String, String> envMapping = new HashMap<>();
        if (Files.exists(base.resolve(ENV_MAPPING_FILE))) {
            try {
                Files.readAllLines(base.resolve(ENV_MAPPING_FILE)).stream().map(line -> line.split("=", 2))
                    .filter(parts -> parts.length == 2).forEach(parts -> {
                        envMapping.put(parts[0].trim(), parts[1].trim());
                    });
            } catch (IOException ex) {
                // if reading fails, we just ignore the mapping, and delete the file
                try {
                    Files.delete(base.resolve(ENV_MAPPING_FILE));
                } catch (IOException e) {
                    LOGGER.warn("Failed to delete corrupted " + ENV_MAPPING_FILE + " file: " + e.getMessage());
                }
            }
        }
        int numEnvs = envMapping.size();

        final String hash = sha256Hex(lockFileContent);

        if (envMapping.containsKey(hash)) {
            return base.resolve(envMapping.get(hash));
        }
        String envDirName = "" + numEnvs;
        envMapping.put(hash, envDirName);

        // write back updated mapping
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : envMapping.entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
        }
        Files.writeString(base.resolve(ENV_MAPPING_FILE), sb.toString());

        var resolvedFromHash = base.resolve(envDirName);
        return resolveWithCollisionHandling(resolvedFromHash, lockFileContent);
    }

    /**
     * Resolves a project directory, handling potential hash collisions by appending suffixes.
     *
     * @param baseDir the base directory path derived from the lock file hash
     * @param lockFileContent the expected lock file content
     * @return a directory path that either doesn't exist yet or contains matching content
     * @throws IOException if an I/O error occurs
     */
    private static Path resolveWithCollisionHandling(final Path baseDir, final String lockFileContent)
        throws IOException {
        Path candidateDir = baseDir;
        int suffix = 0;

        while (suffix < MAX_HASH_ENV_COLLISION_SUFFIX) {
            Files.createDirectories(candidateDir);
            final Path lockFilePath = candidateDir.resolve("pixi.lock");

            // Directory is free to use if no lock file exists yet
            if (!Files.exists(lockFilePath)) {
                return candidateDir;
            }

            // Check if existing lock file matches (no collision)
            final String existingContent = Files.readString(lockFilePath, StandardCharsets.UTF_8);
            if (existingContent.equals(lockFileContent)) {
                return candidateDir;
            }

            // Hash collision detected - try with numeric suffix
            candidateDir = baseDir.resolveSibling(baseDir.getFileName() + "_" + suffix);
            suffix++;
        }
        throw new IOException("Failed to resolve a unique project directory after 10 attempts.");
    }

    private static String sha256Hex(final String s) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute SHA-256 hash.", e);
        }
    }
}
