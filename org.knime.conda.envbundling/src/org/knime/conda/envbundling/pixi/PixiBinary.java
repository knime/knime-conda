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
 *   Apr 28, 2025 (benjaminwilhelm): created
 */
package org.knime.conda.envbundling.pixi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.knime.core.util.FileUtil;
import org.osgi.framework.FrameworkUtil;

/**
 * Utility to locate and invoke the bundled <em>pixi</em> CLI.
 *
 * @author Benjamin Wilhelm, KNIME GmbH, Berlin, Germany
 */
public final class PixiBinary {

    /** Thread‑safe cache for the resolved pixi path. */
    private static final AtomicReference<String> CACHED_PATH = new AtomicReference<>();

    private PixiBinary() {
        /* utility class */
    }

    /**
     * Encapsulates the result of invoking the {@code pixi} CLI.
     *
     * @param isSuccess {@code true} iff {@code returnCode == 0}
     * @param returnCode the process' exit code
     * @param stdout aggregated content written to <em>stdout</em>
     * @param stderr aggregated content written to <em>stderr</em>
     */
    public record CallResult(boolean isSuccess, int returnCode, String stdout, String stderr) {
    }

    /**
     * Returns the absolute path of the bundled <em>pixi</em> executable.
     *
     * @return the absolute path (never {@code null})
     * @throws PixiBinaryLocationException if the binary cannot be located
     */
    public static String getPixiBinaryPath() throws PixiBinaryLocationException {
        // fast path – use cached value when available
        String path = CACHED_PATH.get();
        if (path != null) {
            return path;
        }

        synchronized (CACHED_PATH) {
            // re‑check to avoid double work
            path = CACHED_PATH.get();
            if (path != null) {
                return path;
            }

            var bundle = FrameworkUtil.getBundle(PixiBinary.class);
            if (bundle == null) {
                throw new PixiBinaryLocationException("Cannot determine bundle for PixiBinary class.");
            }

            var fragments = Platform.getFragments(bundle);

            if (fragments == null || fragments.length == 0) {
                throw new PixiBinaryLocationException(
                    "Could not locate pixi binary because no pixi fragment is installed.");
            }
            if (fragments.length > 1) {
                throw new PixiBinaryLocationException(
                    "Could not locate pixi binary because multiple pixi fragments are installed.");
            }

            var url = FileLocator.find(fragments[0], relativePixiPath(), null);
            if (url == null) {
                throw new PixiBinaryLocationException("Could not locate pixi binary inside fragment.");
            }

            try {
                var pixiFile = FileUtil.getFileFromURL(FileLocator.toFileURL(url));
                path = pixiFile.getAbsolutePath();
                CACHED_PATH.set(path);
                return path;
            } catch (IOException ex) {
                throw new PixiBinaryLocationException(
                    "Could not locate pixi binary because the URL could not be converted to a file.", ex);
            }
        }
    }

    private static String readStdstream(final InputStream stream) throws IOException {
        try (var r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return r.lines().collect(Collectors.joining(System.lineSeparator()));
        }
    }

    /**
     * Executes the <em>pixi</em> binary in the given working directory and waits for it to finish.
     * <p>
     * If the process itself finishes and returns a non‑zero exit code, this is <em>not</em> treated as an exception;
     * the {@link CallResult#isSuccess()} flag simply becomes {@code false}.
     *
     * @param workingDirectory the directory that should be used as the process' working directory (must not be
     *            {@code null}
     * @param args additional arguments to pass to the process (excluding the executable itself)
     * @return a {@link CallResult} containing the process' exit status and captured I/O
     * @throws PixiBinaryLocationException if the pixi executable cannot be found
     * @throws IOException if the process cannot be started or its streams cannot be read
     * @throws InterruptedException if the current thread is interrupted while waiting for the process
     */
    public static CallResult callPixi(final java.nio.file.Path workingDirectory, final String... args)
        throws PixiBinaryLocationException, IOException, InterruptedException {
        return callPixi(workingDirectory, null, args);
    }

    /**
     * Executes the <em>pixi</em> binary in the given working directory with optional environment variables.
     *
     * @param workingDirectory the directory that should be used as the process' working directory (must not be null)
     * @param envVars additional environment variables to set (may be null)
     * @param args additional arguments to pass to the process (excluding the executable itself)
     * @return a {@link CallResult} containing the process' exit status and captured I/O
     * @throws PixiBinaryLocationException if the pixi executable cannot be found
     * @throws IOException if the process cannot be started or its streams cannot be read
     * @throws InterruptedException if the current thread is interrupted while waiting for the process
     */
    public static CallResult callPixi(final java.nio.file.Path workingDirectory, final Map<String, String> envVars, final String... args)
        throws PixiBinaryLocationException, IOException, InterruptedException {
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");

        var pixiPath = getPixiBinaryPath();

        var pb = new ProcessBuilder(pixiPath);
        pb.command().addAll(Arrays.asList(args));
        pb.directory(workingDirectory.toFile());
        pb.redirectErrorStream(false);

        if (envVars != null) {
            pb.environment().putAll(envVars);
        }

        var process = pb.start();
        var stdStreamsReadersExec = Executors.newFixedThreadPool(2);

        try {
            // Note that the streams are read in parallel to avoid blocking the process
            var stdoutFuture = stdStreamsReadersExec.submit(() -> readStdstream(process.getInputStream()));
            var stderrFuture = stdStreamsReadersExec.submit(() -> readStdstream(process.getErrorStream()));

            boolean finished = process.waitFor(20, TimeUnit.MINUTES);
            if (!finished) {
                process.destroy();
                throw new IOException("Timed out waiting for pixi to finish");
            }

            // Fully consume the streams
            String stdout;
            String stderr;
            try {
                stdout = stdoutFuture.get();
                stderr = stderrFuture.get();
            } catch (ExecutionException ex) { // NOSONAR - the cause is relevant and re-thrown
                // propagate the *root cause* as an IOException
                throw new IOException("Failed to read pixi output", ex.getCause());
            }

            var returnCode = process.exitValue();
            return new CallResult(returnCode == 0, returnCode, stdout, stderr);
        } finally {
            stdStreamsReadersExec.shutdownNow();
        }
    }

    /** Returns the relative path of the pixi executable inside the fragment bundle. */
    private static Path relativePixiPath() {
        if (Platform.OS_WIN32.equals(Platform.getOS())) {
            return new Path("bin/pixi.exe");
        } else {
            return new Path("bin/pixi");
        }
    }

    /** Checked exception used when the pixi executable cannot be resolved. */
    public static final class PixiBinaryLocationException extends Exception {
        private static final long serialVersionUID = 1L;

        private PixiBinaryLocationException(final String message) {
            super(message);
        }

        private PixiBinaryLocationException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
