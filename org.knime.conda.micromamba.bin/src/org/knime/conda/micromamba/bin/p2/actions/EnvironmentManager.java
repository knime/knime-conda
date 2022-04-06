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
 *   Apr 6, 2022 (Adrian Nembach, KNIME GmbH, Konstanz, Germany): created
 */
package org.knime.conda.micromamba.bin.p2.actions;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.knime.conda.micromamba.bin.MicromambaExecutable;
import org.knime.core.util.PathUtils;

/**
 * Manages the creation and removal of Conda environments.
 *
 * @author Adrian Nembach, KNIME GmbH, Konstanz, Germany
 */
final class EnvironmentManager {

    private final EnvironmentConfig m_config;

    private final Path m_condaExe;

    EnvironmentManager(final EnvironmentConfig config) {
        m_config = config;
        m_condaExe = getCondaExePath();
        ensureCondaIsExecutable(m_condaExe);
    }

    IStatus removeEnvironment() {
        try {
            PathUtils.deleteDirectoryIfExists(m_config.environment());
        } catch (IOException ex) {
            return Logger.createError(ex, "Failed to remove Conda environment located at %s.", m_config.environment());
        }
        // TODO cleanup root?
        return Status.OK_STATUS;
    }

    IStatus createEnvironment() {

        var createCommand = createCommand();
        Logger.logInfo("Start environment creation with command '%s'",
            createCommand.command().stream().collect(Collectors.joining(" ")));
        return executeCommand(createCommand);
    }

    private static IStatus executeCommand(final ProcessBuilder command) {
        try {
            Process p = command.start();
            new Thread(() -> logIfNotBlank("environment creation", "Standard output", consumeStream(p::getInputStream)))
                .start();
            new Thread(() -> logIfNotBlank("environment creation", "Standard error", consumeStream(p::getErrorStream)))
                .start();
            int exitVal = p.waitFor();
            if (exitVal != 0) {
                Logger.logError("ShellExec command exited non-zero exit value: %s", exitVal);
                return Status.CANCEL_STATUS;
            }
            return Status.OK_STATUS;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.logError(e, "Conda environment creation was interrupted.");
        } catch (IOException ex) {
            Logger.logError(ex, "I/O error during environment creation.");
        }
        return Status.CANCEL_STATUS;
    }

    private static void logIfNotBlank(final String operation, final String outputType, final String output) {
        if (!output.isBlank()) {
            Logger.logInfo("%s of %s:\n%s", outputType, operation, output);
        }
    }

    private static String consumeStream(final Supplier<InputStream> streamSupplier) {
        try (var stream = streamSupplier.get()) {
            var writer = new StringWriter();
            IOUtils.copy(stream, writer, StandardCharsets.UTF_8);
            return writer.toString();
        } catch (IOException ex) {
            Logger.logError(ex, "Failed to consume input stream");
            throw new IllegalStateException(ex);
        }
    }

    private static void ensureCondaIsExecutable(final Path condaExePath) {
        var condaExeFile = condaExePath.toFile();
        if (!condaExeFile.canExecute()) {
            Logger.logInfo("CondaExe was not executable, fixing that....");
            condaExeFile.setExecutable(true);//NOSONAR
        }
    }

    private ProcessBuilder createCommand() {
        return new ProcessBuilder(//
            quote(m_condaExe), "create", // micromamba create
            "-p", quote(m_config.environment()), // path to the newly created environment
            "-c", encodeAsUrl(m_config.channel()), "--override-channels", // only use the local channel
            "-r", quote(getRootPath()), // set the micromamba root
            "-f", quote(m_config.environmentDefinition()), // set the environment definition file
            "--platform", getPlatform(), // set the current platform (needed for Mac arm64)
            "-y"// don't ask for permission
        );
    }

    private static String encodeAsUrl(final Path localPath) {
        return localPath.toUri().toString();
    }

    private static Path getRootPath() {
        return getCondaExePath().getParent().resolve("root");
    }

    private static String quote(final Path string) {
        return "\"" + string.toAbsolutePath().toString() + "\"";
    }

    private static String getPlatform() {
        var os = getOS();
        var arch = getArch();
        return os + "-" + arch;
    }

    private static String getOS() {
        var os = Platform.getOS();
        // TODO use switch with pattern matching in Java 17
        if (Platform.OS_WIN32.equals(os)) {
            return "win";
        } else if (Platform.OS_LINUX.equals(os)) {
            return "linux";
        } else if (Platform.OS_MACOSX.equals(os)) {
            return "osx";
        } else {
            throw new IllegalStateException("Unsupported OS: " + os);
        }
    }

    private static String getArch() {
        var arch = Platform.getOSArch();
        // TODO use switch with pattern matching in Java 17
        if (Platform.ARCH_X86_64.equals(arch)) {
            return "64";
        } else if ("aarch64".equals(arch)) { // use constant in Java 17
            return "arm64";
        } else {
            throw new IllegalStateException("Unsupported arch: " + arch);
        }
    }

    private static Path getCondaExePath() throws IllegalStateException {
        return MicromambaExecutable.getInstance().getPath();
    }
}
