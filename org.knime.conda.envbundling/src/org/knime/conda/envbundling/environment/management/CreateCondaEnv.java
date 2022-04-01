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
 *   Apr 1, 2022 (Adrian Nembach, KNIME GmbH, Konstanz, Germany): created
 */
package org.knime.conda.envbundling.environment.management;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.equinox.p2.engine.spi.ProvisioningAction;
import org.knime.conda.micromamba.bin.MicromambaExecutable;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Installs the Conda environment contained in a plugin. Assumes that the plugin contains an env.yml file with the
 * environment definition, and a Conda channel containing all dependencies.
 *
 * @author Adrian Nembach, KNIME GmbH, Konstanz, Germany
 */
public class CreateCondaEnv extends ProvisioningAction {

    private static final String ARTIFACT_LOCATION = "artifactLocation";

    private static final Bundle BUNDLE = FrameworkUtil.getBundle(CreateCondaEnv.class);

    private static final ILog LOGGER = Platform.getLog(BUNDLE);

    @Override
    public IStatus execute(final Map<String, Object> parameters) {
        var config = createConfig(parameters);
        if (config != null) {
            return createEnvironment(config);
        } else {
            return Status.CANCEL_STATUS;
        }
    }

    private static Config createConfig(final Map<String, Object> parameters) {
        if (parameters.containsKey(ARTIFACT_LOCATION)) {
            var artifactLocation = Paths.get((String)parameters.get(ARTIFACT_LOCATION));
            var config = new Config(artifactLocation);
            var error = validateConfig(config);
            if (error != null) {
                return config;
            } else {
                logError(error);
            }
        } else {
            logError("The parameters must contain the artifactLocation parameter.");
        }
        return null;
    }

    private static String validateConfig(final Config config) {
        if (!Files.exists(config.conda)) {
            return "The conda executable file does not exist: " + config.conda;
        }
        if (!Files.exists(config.channel)) {
            return "There is no channel in the plugin: " + config.channel;
        }
        if (!Files.exists(config.envYml)) {
            return "There is no env.yml file in the plugin.";
        }
        return null;
    }

    private static IStatus createEnvironment(final Config config) {
        ensureCondaIsExecutable(config.conda);
        var createCommand = createCommand(config);
        logInfo("Start environment creation with command '%s'", createCommand.command());
        return executeCommand(createCommand);
        // TODO cleanup i.e. remove channel and micromamba executable (if it is part of the plugin)
    }

    private static IStatus executeCommand(final ProcessBuilder createCommand) {
        try {
            Process p = createCommand.start();
            int exitVal = p.waitFor();
            if (exitVal != 0) {
                logError("ShellExec command exited non-zero exit value: %s", exitVal);
                return Status.CANCEL_STATUS;
            }
            return Status.OK_STATUS;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logError(e, "Conda environment creation was interrupted.");
        } catch (IOException ex) {
            logError(ex, "I/O error during environment creation.");
        }
        return Status.CANCEL_STATUS;
    }

    private static void ensureCondaIsExecutable(final Path condaExePath) {
        var condaExeFile = condaExePath.toFile();
        if (!condaExeFile.canExecute()) {
            logInfo("CondaExe was not executable, fixing that....");
            condaExeFile.setExecutable(true);//NOSONAR
        }
    }

    private static void logInfo(final String format, final Object... args) {
        LOGGER.log(new Status(IStatus.INFO, BUNDLE.getSymbolicName(), String.format(format, args)));
    }

    private static void logError(final String format, final Object... args) {
        LOGGER.log(new Status(IStatus.ERROR, BUNDLE.getSymbolicName(), String.format(format, args)));
    }

    private static void logError(final Throwable cause, final String format, final Object... args) {
        LOGGER.log(new Status(IStatus.ERROR, BUNDLE.getSymbolicName(), String.format(format, args), cause));
    }

    private static ProcessBuilder createCommand(final Config config) {
        return new ProcessBuilder(//
            quote(config.conda), "create", // micromamba create
            "-p", quote(config.env), // path to the newly created environment
            "-c", encodeAsUrl(config.channel), "--override-channels", // only use the local channel
            "-r", quote(getRootPath()), // set the micromamba root
            "-f", quote(config.envYml), // set the environment definition file
            "--platform", getPlatform(), // set the current platform (needed for Mac arm64)
            "-y"// don't ask for permission
        );
    }

    private static String encodeAsUrl(final Path localPath) {
        return localPath.toUri().toString();
    }

    private static Path getRootPath() {
        return Path.of(BUNDLE.getLocation(), "micromamba", "root"); //TODO check if this actually works
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
        // TODO this assumes that the plugin providing micromamba is already installed (i.e. is part of the minimal product)
        // Alternatively, we can also put it into every bundled environment
        return MicromambaExecutable.getInstance().getPath();
    }

    @Override
    public IStatus undo(final Map<String, Object> parameters) {
        // TODO: implement me? delete conda env!
        return Status.OK_STATUS;
    }

    private static class Config {

        private final Path conda;

        private final Path env;

        private final Path channel;

        private final Path envYml;

        Config(final Path artifactPath) {
            conda = getCondaExePath();
            env = artifactPath.resolve("env");
            channel = artifactPath.resolve("channel");
            envYml = artifactPath.resolve("env.yml");
        }
    }

}
