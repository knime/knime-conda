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
 *   Mar 13, 2023 (benjamin): created
 */
package org.knime.conda.envbundling.environment;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.knime.conda.envbundling.CondaEnvironmentBundlingUtils;
import org.knime.core.node.NodeLogger;

/**
 * Utility for collecting and downloading the packages of installed Conda environments.
 *
 * @author Benjamin Wilhelm, KNIME GmbH, Berlin, Germany
 */
public final class CondaPackageCollectionUtil {

    private static final String[] PLATFORM_IDS = {"linux-64", "win-64", "osx-64", "osx-arm64"};

    private static final String TARGET_FOLDER_CONDA = "conda";

    private static final String TARGET_FOLDER_PIP = "pip";

    private static final String CONDA_PKG_URLS_PATH = "pkg_urls/conda_%s.txt";

    private static final String PIP_PKG_URLS_PATH = "pkg_urls/pip_%s.txt";

    private CondaPackageCollectionUtil() {
        // Only static functions
    }

    /**
     * Collect and download the Conda and pip packages of all installed environments that do not bundle the packages.
     * The packages will be saved to the folders "conda" and "pip" in the given target folder. Files that already exist
     * in the appropriate location will not be downloaded.
     *
     * @param targetFolder
     * @param logger a consumer that receives status updates
     */
    public static void collectAndDownloadPackages(final Path targetFolder, final Consumer<String> logger) {
        try {
            new PackageCollector(targetFolder, logger).collect();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            NodeLogger.getLogger(CondaPackageCollectionUtil.class).warn("Python package download was interrupted", ex);
            logger.accept("ERROR - Download interrupted");
        }
    }

    private static final class PackageCollector {

        private final Path m_targetFolder;

        private final Consumer<String> m_logger;

        private PackageCollector(final Path targetFolder, final Consumer<String> logger) {
            m_targetFolder = targetFolder;
            m_logger = logger;
        }

        private void collect() throws InterruptedException {
            info("Collecting package URLs");
            final var pkgsToDownload = collectPkgToDownload();

            // Download the packages
            try {
                if (pkgsToDownload.isEmpty()) {
                    info("No packages to download");
                } else {
                    info("Downloading packages");
                    for (var downloadInfo : pkgsToDownload) {
                        downloadPkg(downloadInfo, m_targetFolder);
                        throwIfInterrupted();
                    }
                }

                info("DONE");
            } catch (final IOException ex) {
                error(String.format("%s%n%n%s", ex.getMessage(), getStacktrace(ex)));
            }
        }

        /** Collect the package URLs for all conda and pip packages that need to be downloaded */
        private Collection<DownloadInfo> collectPkgToDownload() throws InterruptedException {
            Map<String, CondaEnvironment> allEnvironments = CondaEnvironmentRegistry.getEnvironments();
            Set<DownloadInfo> packages = new HashSet<>();

            for (var env : allEnvironments.values()) {
                if (env.requiresDownload()) {
                    info(String.format("Collecting package URLs for environment %s at %s", env.getName(),
                        env.getPath()));
                    for (var platformId : PLATFORM_IDS) {
                        packages.addAll(getCondaPkgUrls(env, platformId));
                        packages.addAll(getPipPkgUrls(env, platformId));
                    }
                } else {
                    info(String.format("Environment %s at %s includes all required packages in the bundle. "
                        + "No packages to download.", env.getName(), env.getPath()));
                }
                throwIfInterrupted();
            }

            return packages;
        }

        /**
         * Get the URLs of the conda packages of the given conda environment. Empty if the packages are bundled.
         */
        private Set<DownloadInfo> getCondaPkgUrls(final CondaEnvironment env, final String platformId) {
            var relativePath = Path.of(TARGET_FOLDER_CONDA, platformId);
            try {
                var pkgUrlsFile = CondaEnvironmentBundlingUtils.getAbsolutePath(env.getBundle(),
                    CONDA_PKG_URLS_PATH.formatted(platformId));
                try (var reader = Files.newBufferedReader(pkgUrlsFile)) {
                    var pkgUrls = reader.lines() //
                        .filter(l -> !("@EXPLICIT".equals(l))) //
                        .map(l -> {
                            var splitIdx = l.lastIndexOf('#');
                            return new DownloadInfo(l.substring(0, splitIdx), l.substring(splitIdx + 1), relativePath);
                        }) //
                        .collect(Collectors.toSet());
                    info(String.format("Read Conda package URLs for %s from %s", env.getName(), pkgUrlsFile));
                    return pkgUrls;
                }
            } catch (final IOException ex) { // NOSONAR (exception message logged in the next line)
                error(String.format("Could not find conda package list for %s (platform: %s): %s", env.getName(),
                    platformId, ex.getMessage()));
                return Collections.emptySet();
            }
        }

        /**
         * Get the URLs of the pip packages of the given conda environment. Empty if the packages are bundled or if
         * there are none.
         */
        private Set<DownloadInfo> getPipPkgUrls(final CondaEnvironment env, final String platformId) {
            Path pkgUrlsFile;
            try {
                pkgUrlsFile = CondaEnvironmentBundlingUtils.getAbsolutePath(env.getBundle(),
                    PIP_PKG_URLS_PATH.formatted(platformId));
            } catch (IOException ex) { // NOSONAR
                // NB: The file does not exist -> We do not have pip packages
                info(String.format("No pip packages to download for %s (platform %s)", env.getName(), platformId));
                return Collections.emptySet();
            }

            var relativePath = Path.of(TARGET_FOLDER_PIP);
            try (var reader = Files.newBufferedReader(pkgUrlsFile)) {
                var pkgUrls = reader.lines() //
                    .map(l -> new DownloadInfo(l, null, relativePath)) //
                    .collect(Collectors.toSet());
                info(String.format("Read pip package URLs for %s from %s", env.getName(), pkgUrlsFile));
                return pkgUrls;
            } catch (final IOException ex) { // NOSONAR (exception message logged in the next line)
                error(String.format("Could not read pip package list for %s (platform %s): %s", env.getName(),
                    platformId, ex.getMessage()));
                return Collections.emptySet();
            }
        }

        /** Download the package from the given URL and check the md5 sum */
        private void downloadPkg(final DownloadInfo downloadInfo, final Path targetFolder) throws IOException {
            var url = new URL(downloadInfo.url);
            var relativeFilePath = downloadInfo.relativePath.resolve(FilenameUtils.getName(url.getPath()));
            var targetFile = targetFolder.resolve(relativeFilePath);

            if (Files.exists(targetFile)) {
                // Do not download if the file already exist
                // Only check the checksum if it is available
                info(String.format("File %s already downloaded", downloadInfo.url));
                checkMd5Sum(targetFile, downloadInfo.md5,
                    "The file %s already existed but the checksum did not match. Expected %s, got %s.");
                return;
            }

            info(String.format("Downloading %s", downloadInfo.url));
            FileUtils.copyURLToFile(url, targetFile.toFile());
            checkMd5Sum(targetFile, downloadInfo.md5,
                "Checksum of downloaded file %s did not match. Expected %s, got %s.");
        }

        /** Checks if the file has the given checksum. If expectedMd5 is null, nothing will be checked */
        private void checkMd5Sum(final Path file, final String expectedMd5, final String errorMessage)
            throws IOException {
            if (expectedMd5 != null) {
                try (var in = Files.newInputStream(file)) {
                    var md5 = DigestUtils.md5Hex(in); // NOSONAR
                    if (!expectedMd5.equals(md5)) {
                        error(String.format(errorMessage, file, expectedMd5, md5));
                    }
                }
            }
        }

        /** Get the stacktrace as a String */
        private static String getStacktrace(final Throwable t) {
            var sw = new StringWriter();
            var pw = new PrintWriter(sw);
            t.printStackTrace(pw);
            return sw.toString();
        }

        /** @throws InterruptedException if the current thread is interrupted */
        private static void throwIfInterrupted() throws InterruptedException {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }

        private static record DownloadInfo(String url, String md5, Path relativePath) {
        }

        private void info(final String message) {
            m_logger.accept("INFO - " + message);
        }

        private void error(final String message) {
            m_logger.accept("ERROR - " + message);
        }
    }
}
