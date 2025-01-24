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
 *   Dec 8, 2020 (marcel): created
 */
package org.knime.conda;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.NodeLogger;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

class CondaExecutionMonitor {

    private static final String MONITOR_TIMEOUT_VM_OPTION = "knime.conda.lightweightCommandTimeout";

    private static final int DEFAULT_MONITOR_TIMEOUT_IN_SECONDS = 30;

    private final List<String> m_standardOutputErrors = new ArrayList<>();

    private final List<String> m_errorOutputErrors = new ArrayList<>();

    /**
     * May be {@code < 0}, which means that no timeout is specified.
     */
    private final long m_timeoutInSeconds;

    private boolean m_isCanceled;

    private final Consumer<String> m_nodeErrorMessageConsumer;

    private String m_lastNodeErrorMessage;

    public CondaExecutionMonitor() {
        this(false);
    }

    /**
     * @param monitorsLightweightCommand {@code true} if this monitor is intended to monitor lightweight conda commands
     *            that are expected to finish in short time. Enable this option to run
     *            {@code #monitorExecution(Process, boolean)} within a predefined duration after which the monitor times
     *            out and causes a {@link CondaCanceledExecutionException cancellation}. This can help to avoid blocking
     *            subsequent logic if conda is slow or non-responsive. Set this parameter to {@code false} if the
     *            monitored command is expected to be time consuming (e.g. environment creation).
     */
    public CondaExecutionMonitor(final boolean monitorsLightweightCommand) {
        this(monitorsLightweightCommand, s -> {
        });
    }

    /**
     * @param monitorsLightweightCommand {@code true} if this monitor is intended to monitor lightweight conda commands
     *            that are expected to finish in short time. Enable this option to run
     *            {@code #monitorExecution(Process, boolean)} within a predefined duration after which the monitor times
     *            out and causes a {@link CondaCanceledExecutionException cancellation}. This can help to avoid blocking
     *            subsequent logic if conda is slow or non-responsive. Set this parameter to {@code false} if the
     *            monitored command is expected to be time consuming (e.g. environment creation).
     * @param nodeErrorMessageConsumer A consumer that should show this error as node error
     */
    public CondaExecutionMonitor(final boolean monitorsLightweightCommand,
        final Consumer<String> nodeErrorMessageConsumer) {
        if (monitorsLightweightCommand) {
            m_timeoutInSeconds = getLightweightCommandTimeoutInSeconds();
        } else {
            m_timeoutInSeconds = -1;
        }
        m_nodeErrorMessageConsumer = nodeErrorMessageConsumer;
    }

    private static int getLightweightCommandTimeoutInSeconds() {
        try {
            final String timeout =
                System.getProperty(MONITOR_TIMEOUT_VM_OPTION, Integer.toString(DEFAULT_MONITOR_TIMEOUT_IN_SECONDS));
            return Integer.parseInt(timeout);
        } catch (final NumberFormatException ex) {
            NodeLogger.getLogger(CondaExecutionMonitor.class)
                .warn("The VM option -D" + MONITOR_TIMEOUT_VM_OPTION
                    + " was set to an invalid, non-integer value. The timeout therefore defaults to "
                    + DEFAULT_MONITOR_TIMEOUT_IN_SECONDS + " sec.");
            return DEFAULT_MONITOR_TIMEOUT_IN_SECONDS;
        }
    }

    void monitorExecution(final Process conda, final boolean hasJsonOutput)
        throws CondaCanceledExecutionException, IOException {
        final var outputListener = new Thread(() -> parseOutputStream(conda.getInputStream(), hasJsonOutput));
        final var errorListener = new Thread(() -> parseErrorStream(conda.getErrorStream()));
        try {
            outputListener.start();
            errorListener.start();
            final int condaExitCode = awaitTermination(conda);
            if (condaExitCode != 0) {
                // Wait for listeners to finish consuming their streams before creating the error message.
                try {
                    outputListener.join();
                    errorListener.join();
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new CondaCanceledExecutionException(ex);
                }

                // If we already have an error message set that caused conda to fail, use that, otherwise
                // tell users about the exit code.
                final String errorMessage =
                    m_lastNodeErrorMessage == null ? createErrorMessage(condaExitCode) : m_lastNodeErrorMessage;
                throw new IOException(errorMessage);
            }
        } finally {
            outputListener.interrupt();
            errorListener.interrupt();
        }
    }

    void setNodeError(final String message) {
        m_lastNodeErrorMessage = message;
        m_nodeErrorMessageConsumer.accept(message);
    }

    private void parseOutputStream(final InputStream standardOutput, final boolean isJsonOutput) {
        try {
            if (isJsonOutput) {
                parseJsonOutput(standardOutput);
            } else {
                parseNonJsonOutput(standardOutput);
            }
        } catch (final IOException ex) {
            if (!isCanceledOrInterrupted()) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    private void parseJsonOutput(final InputStream standardOutput) throws IOException {
        try (final JsonParser parser =
            new JsonFactory(new ObjectMapper()).createParser(new BufferedInputStream(standardOutput))) {
            while (!isCanceledOrInterrupted()) {
                try {
                    final TreeNode json = parser.readValueAsTree();
                    if (json == null) {
                        // EOF
                        break;
                    }
                    final String errorMessage = parseErrorFromJsonOutput(json);
                    if (!errorMessage.isEmpty()) {
                        m_standardOutputErrors.add(errorMessage);
                        handleErrorMessage(errorMessage);
                    } else {
                        handleCustomJsonOutput(json);
                    }
                }
                // No Sonar: Receiving improper output from Conda is expected. Catching an exception here is part of the
                // normal control flow.
                catch (final JsonParseException ex) { // NOSONAR
                    // Ignore and continue; wait for proper output.
                }
            }
        }
    }

    private static String parseErrorFromJsonOutput(final TreeNode json) {
        String errorMessage = "";
        final TreeNode error = json.get("error");
        if (error != null) {
            final TreeNode exceptionName = json.get("exception_name");
            if (exceptionName != null && "ResolvePackageNotFound".equals(((JsonNode)exceptionName).textValue())) {
                errorMessage += "Failed to resolve the following list of packages.\nPlease make sure these "
                    + "packages are available for the local platform or exclude them from the creation process.";
            }
            final TreeNode message = json.get("message");
            if (message != null) {
                errorMessage += ((JsonNode)message).textValue();
            } else {
                errorMessage += ((JsonNode)error).textValue();
            }
            final TreeNode reason = json.get("reason");
            if (reason != null && ((JsonNode)reason).textValue().equals("CONNECTION FAILED")) {
                errorMessage += "\nPlease check your internet connection.";
            }
        }
        return errorMessage;
    }

    /**
     * Asynchronous callback that allows to process error messages (usually just one line at a time) of the monitored
     * Conda command.<br>
     * Exceptions thrown by this callback are discarded.
     *
     * @param error The error message, neither {@code null} nor empty.
     */
    protected void handleErrorMessage(final String error) {
        // Do nothing by default.
    }

    /**
     * Asynchronous callback that allows to process custom JSON output of the monitored Conda command.<br>
     * Exceptions thrown by this callback are discarded.
     *
     * @param json The node that represents the root element of the read JSON output. Not {@code null}.
     */
    void handleCustomJsonOutput(final TreeNode json) {
        // Do nothing by default.
    }

    private void parseNonJsonOutput(final InputStream standardOutput) {
        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(standardOutput))) {
            String line;
            while (!isCanceledOrInterrupted() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.equals("")) {
                    handleCustomNonJsonOutput(line);
                }
            }
        } catch (final IOException ex) {
            if (!isCanceledOrInterrupted()) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    /**
     * Asynchronous callback that allows to process custom non-JSON output messages (usually just one line at a time) of
     * the monitored Conda command.<br>
     * Exceptions thrown by this callback are discarded.
     *
     * @param output The output message, neither {@code null} nor empty.
     */
    void handleCustomNonJsonOutput(final String output) {
        // Do nothing by default.
    }

    private void parseErrorStream(final InputStream errorOutput) {
        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(errorOutput))) {
            String line;
            boolean inWarning = false;
            while (!isCanceledOrInterrupted() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.equals("")) {
                    inWarning = inWarning || line.startsWith("==> WARNING: A newer version of conda exists. <==");
                    if (inWarning) {
                        handleWarningMessage(line);
                    } else {
                        m_errorOutputErrors.add(line);
                        handleErrorMessage(line);
                    }
                    inWarning = inWarning && !line.startsWith("$ conda update -n base");
                }
            }
        } catch (final IOException ex) {
            if (!isCanceledOrInterrupted()) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    /**
     * Asynchronous callback that allows to process warning messages (usually just one line at a time) of the monitored
     * Conda command.<br>
     * Exceptions thrown by this callback are discarded.
     *
     * @param warning The warning message, neither {@code null} nor empty.
     */
    protected void handleWarningMessage(final String warning) {
        // Do nothing by default.
    }

    private int awaitTermination(final Process conda) throws CondaCanceledExecutionException, IOException {
        final ExecutorService executor = Executors.newSingleThreadExecutor(
            new ThreadFactoryBuilder().setNameFormat("conda-" + conda.pid() + "-await-termination-%d").build());
        try {
            return executeCancelable(() -> waitForConda(conda), executor::submit, this);
        } catch (final CondaCanceledExecutionException ex) {
            handleCanceledExecution(conda);
            throw ex;
        }
    }

    private int waitForConda(final Process conda) throws InterruptedException, CondaCanceledExecutionException {
        if (m_timeoutInSeconds < 0) {
            return conda.waitFor();
        } else {
            if (conda.waitFor(m_timeoutInSeconds, TimeUnit.SECONDS)) {
                return conda.exitValue();
            } else {
                throw new CondaCanceledExecutionException("Waiting for the conda command to finish timed out after "
                    + m_timeoutInSeconds + " sec.\nPlease consider increasing the timeout using the VM option '-D"
                    + MONITOR_TIMEOUT_VM_OPTION
                    + "=<value-in-seconds>'.\nIt is also advisable to check why conda was so unusually slow in "
                    + "executing the command (e.g. due to high CPU load?).");
            }
        }
    }

    protected void handleCanceledExecution(final Process conda) {
        // Destroy the process by default
        // NOTE: On Windows subprocesses will not be killed
        conda.destroy();
    }

    private String createErrorMessage(final int condaExitCode) {
        String errorMessage = null;
        if (!m_standardOutputErrors.isEmpty()) {
            errorMessage = String.join("\n", m_standardOutputErrors);
        }
        if (!m_errorOutputErrors.isEmpty()) {
            final String detailMessage = String.join("\n", m_errorOutputErrors);
            if (errorMessage == null) {
                errorMessage = "Failed to execute Conda";
                if (detailMessage.contains("CONNECTION FAILED") && detailMessage.contains("SSLError")) {
                    errorMessage += ". Please uninstall and reinstall Conda.\n";
                } else {
                    errorMessage += ":\n";
                }
                errorMessage += detailMessage;
            } else {
                errorMessage += "\nAdditional output: " + detailMessage;
            }
        }
        if (errorMessage == null) {
            errorMessage = "Conda process terminated with error code " + condaExitCode + ".";
        }
        return errorMessage;
    }

    /**
     * Cancels the execution of the monitored conda command.
     */
    public synchronized void cancel() {
        m_isCanceled = true;
    }

    protected synchronized boolean isCanceledOrInterrupted() {
        return m_isCanceled || Thread.currentThread().isInterrupted();
    }

    // TODO the following methods are copied and slightly modified from the PythonUtils: Conciliate

    private static <T> T executeCancelable(final Callable<T> task, final Function<Callable<T>, Future<T>> submitTask,
        final CondaExecutionMonitor monitor) throws CondaCanceledExecutionException, IOException {
        final Future<T> future = submitTask.apply(task);
        // Wait until execution is done or cancelled.
        final int waitTimeoutMilliseconds = 1000;
        while (true) {
            try {
                return future.get(waitTimeoutMilliseconds, TimeUnit.MILLISECONDS);
            } catch (final TimeoutException ex) { // NOSONAR: Timeout is expected and part of control flow.
                executeCancelableCheckCanceled(future, monitor);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                executeCancelableCheckCanceled(future, monitor);
            } catch (final CancellationException ex) {
                // Should not happen since the handle to the future is local to this method.
                NodeLogger.getLogger(CondaExecutionMonitor.class).debug(ex);
                throw new CondaCanceledExecutionException();
            } catch (final ExecutionException wrapper) {
                executeCancelableUnwrapExecutionException(wrapper);
            }
        }
    }

    private static <T> void executeCancelableCheckCanceled(final Future<T> future, final CondaExecutionMonitor monitor)
        throws CondaCanceledExecutionException {
        if (monitor.isCanceledOrInterrupted()) {
            future.cancel(true);
            throw new CondaCanceledExecutionException();
        }
    }

    private static void executeCancelableUnwrapExecutionException(final ExecutionException wrapper)
        throws CondaCanceledExecutionException, IOException {
        final Throwable ex = unwrapExecutionException(wrapper).orElse(wrapper);
        if (ex instanceof CondaCanceledExecutionException) {
            // May happen if the executed task checks for cancellation itself.
            throw (CondaCanceledExecutionException)ex;
        } else if (ex instanceof CanceledExecutionException || ex instanceof CancellationException) {
            // May happen if the executed task checks for cancellation itself.
            throw new CondaCanceledExecutionException(ex.getMessage());
        } else if (ex instanceof IOException) {
            throw (IOException)ex;
        } else {
            throw new IOException( //
                ex.getMessage(), // NOSONAR Guaranteed to be non-null.
                ex);
        }
    }

    private static Optional<Throwable> unwrapExecutionException(final Throwable throwable) {
        return traverseStackUntilFound(throwable, t -> t instanceof ExecutionException ? null : t);
    }

    private static <T> Optional<T> traverseStackUntilFound(final Throwable throwable,
        final Function<Throwable, T> visitor) {
        if (throwable == null) {
            return Optional.empty();
        }
        Throwable t = throwable;
        do {
            final T visitOutcome = visitor.apply(t);
            if (visitOutcome != null) {
                return Optional.of(visitOutcome);
            }
        } while (t != t.getCause() && (t = t.getCause()) != null);
        return Optional.empty();
    }
}
