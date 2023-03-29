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

/**
 * Utility for collecting and downloading the packages of installed Conda environments.
 *
 * @author Benjamin Wilhelm, KNIME GmbH, Berlin, Germany
 */
public final class CondaPackageCollectionUtil {

    private static final String TARGET_FOLDER_CONDA = "conda";

    private static final String TARGET_FOLDER_PIP = "pip";

    private static final String CONDA_PKG_URLS_FILENAME = "conda_pkg_urls.txt";

    private static final String PIP_PKG_URLS_FILENAME = "pip_pkg_urls.txt";

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
        new PackageCollector(targetFolder, logger).collect();
    }

    private static final class PackageCollector {

        private final Path m_targetFolder;

        private final Consumer<String> m_logger;

        private PackageCollector(final Path targetFolder, final Consumer<String> logger) {
            m_targetFolder = targetFolder;
            m_logger = logger;
        }

        private void collect() {
            info("Collecting package URLs");
            final var condaPkgUrls = new HashSet<UrlInfo>();
            final var pipPkgUrls = new HashSet<UrlInfo>();
            collectPkgUrls(condaPkgUrls, pipPkgUrls);

            // Download the packages
            try {
                // Conda
                info("Downloading Conda packages");
                var targetConda = m_targetFolder.resolve(TARGET_FOLDER_CONDA)
                    .resolve(CondaEnvironmentBundlingUtils.getCondaPlatformIdentifier());
                Files.createDirectories(targetConda);
                for (var urlInfo : condaPkgUrls) {
                    downloadPkg(targetConda, urlInfo);
                }

                // Pip
                info("Downloading pip packages");
                var targetPip = m_targetFolder.resolve(TARGET_FOLDER_PIP);
                Files.createDirectories(targetConda);
                for (var urlInfo : pipPkgUrls) {
                    downloadPkg(targetPip, urlInfo);
                }

                info("DONE");
            } catch (final IOException ex) {
                error(String.format("%s%n%n%s", ex.getMessage(), getStacktrace(ex)));
            }
        }

        /**
         * Collect the package URLs for all conda and pip packages that need to be downloaded and put them into the
         * given collections
         */
        private void collectPkgUrls(final Collection<UrlInfo> condaPkgUrls, final Collection<UrlInfo> pipPkgUrls) {
            Map<String, CondaEnvironment> allEnvironments = CondaEnvironmentRegistry.getEnvironments();
            for (var env : allEnvironments.values()) {
                info(String.format("Collecting package URLs for environment %s at %s", env.getName(), env.getPath()));
                condaPkgUrls.addAll(getCondaPkgUrls(env));
                pipPkgUrls.addAll(getPipPkgUrls(env));
            }
        }

        /**
         * Get the URLs of the conda packages of the given conda environment. Empty if the packages are bundled.
         */
        private Set<UrlInfo> getCondaPkgUrls(final CondaEnvironment env) {
            try {
                var pkgUrlsFile =
                    CondaEnvironmentBundlingUtils.getAbsolutePath(env.getBundle(), CONDA_PKG_URLS_FILENAME);
                try (var reader = Files.newBufferedReader(pkgUrlsFile)) {
                    var pkgUrls = reader.lines() //
                        .filter(l -> !("@EXPLICIT".equals(l))) //
                        .map(l -> {
                            var splitIdx = l.lastIndexOf('#');
                            return new UrlInfo(l.substring(0, splitIdx), l.substring(splitIdx + 1));
                        }) //
                        .collect(Collectors.toSet());
                    info(String.format("Read Conda package URLs for %s from %s", env.getName(), pkgUrlsFile));
                    return pkgUrls;
                }
            } catch (final IOException ex) { // NOSONAR (exception message logged in the next line)
                warn(String.format("Could not find conda package list for %s: %s", env.getName(), ex.getMessage()));
                return Collections.emptySet();
            }
        }

        /**
         * Get the URLs of the pip packages of the given conda environment. Empty if the packages are bundled or if
         * there are none.
         */
        private Set<UrlInfo> getPipPkgUrls(final CondaEnvironment env) {
            try {
                var pkgUrlsFile = CondaEnvironmentBundlingUtils.getAbsolutePath(env.getBundle(), PIP_PKG_URLS_FILENAME);
                try (var reader = Files.newBufferedReader(pkgUrlsFile)) {
                    var pkgUrls = reader.lines() //
                        .map(l -> new UrlInfo(l, null)) //
                        .collect(Collectors.toSet());
                    info(String.format("Read pip package URLs for %s from %s", env.getName(), pkgUrlsFile));
                    return pkgUrls;
                }
            } catch (final IOException ex) { // NOSONAR (exception message logged in the next line)
                info(String.format("Could not find pip package list for %s: %s", env.getName(), ex.getMessage()));
                return Collections.emptySet();
            }
        }

        /** Download the package from the given URL and check the md5 sum */
        private void downloadPkg(final Path targetFolder, final UrlInfo urlInfo) throws IOException {
            info(String.format("Downloading %s", urlInfo.url));
            var url = new URL(urlInfo.url);
            var targetFile = targetFolder.resolve(FilenameUtils.getName(url.getPath()));

            if (Files.exists(targetFile)) {
                // Do not download if the file already exist
                // Only check the checksum if it is available
                checkMd5Sum(targetFile, urlInfo.md5,
                    "The file %s already existed but the checksum did not match. Expected %s, got %s.");
                return;
            }

            FileUtils.copyURLToFile(url, targetFile.toFile());
            checkMd5Sum(targetFile, urlInfo.md5, "Checksum of downloaded file %s did not match. Expected %s, got %s.");
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

        private static record UrlInfo(String url, String md5) {
        }

        private void info(final String message) {
            m_logger.accept("INFO - " + message);
        }

        private void warn(final String message) {
            m_logger.accept("WARN - " + message);
        }

        private void error(final String message) {
            m_logger.accept("ERROR - " + message);
        }
    }
}
