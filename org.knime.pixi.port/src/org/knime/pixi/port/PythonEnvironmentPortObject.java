package org.knime.pixi.port;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import javax.swing.JComponent;

import org.knime.conda.envinstall.pixi.PixiBinary;
import org.knime.conda.envinstall.pixi.PixiBinary.PixiBinaryLocationException;
import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.ExecutionMonitor;
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.ModelContentRO;
import org.knime.core.node.ModelContentWO;
import org.knime.core.node.NodeLogger;
import org.knime.core.node.port.AbstractSimplePortObject;
import org.knime.core.node.port.PortObjectSpec;
import org.knime.core.node.port.PortType;
import org.knime.core.node.port.PortTypeRegistry;
import org.knime.externalprocessprovider.ExternalProcessProvider;

/**
 * Port object containing information about a Python environment.
 *
 * @author Marc Lehner, KNIME GmbH, Zurich, Switzerland
 * @since 5.11
 */
public final class PythonEnvironmentPortObject extends AbstractSimplePortObject {

    /** The port type for Python environment port objects. */
    public static final PortType TYPE = PortTypeRegistry.getInstance().getPortType(PythonEnvironmentPortObject.class);

    private static final NodeLogger LOGGER = NodeLogger.getLogger(PythonEnvironmentPortObject.class);

    private static final String CFG_PIXI_TOML_CONTENT = "pixi_toml_content";

    private static final String CFG_PIXI_LOCK_CONTENT = "pixi_lock_content";

    /**
     * Global map of installation futures keyed by installation directory path. This ensures that concurrent
     * installations to the same directory are serialized, as Pixi does not support multiple concurrent installations to
     * the same location.
     * <p>
     * Package-private for testing via the {@code org.knime.pixi.port.tests} fragment.
     */
    static final ConcurrentHashMap<Path, CompletableFuture<Void>> GLOBAL_INSTALL_FUTURES =
        new ConcurrentHashMap<>();

    private String m_pixiTomlContent;

    private String m_pixiLockContent;

    /**
     * Serializer for {@link PythonEnvironmentPortObject}.
     */
    public static final class Serializer extends AbstractSimplePortObjectSerializer<PythonEnvironmentPortObject> {
    }

    /**
     * Public no-arg constructor for deserialization.
     */
    public PythonEnvironmentPortObject() {
    }

    /**
     * Constructor.
     *
     * @param pixiTomlContent the content of the pixi.toml file
     * @param pixiLockContent the content of the pixi.lock file
     */
    public PythonEnvironmentPortObject(final String pixiTomlContent, final String pixiLockContent) {
        m_pixiTomlContent = pixiTomlContent;
        m_pixiLockContent = pixiLockContent;
    }

    /**
     * @return the path to the pixi project directory on disk (containing pixi.toml and .pixi/)
     * @throws IOException if the path cannot be resolved
     */
    private Path getPixiEnvironmentPath() throws IOException {
        if (m_pixiLockContent == null || m_pixiLockContent.isBlank()) {
            throw new IllegalArgumentException("pixi.lock content is empty.");
        }
        return PixiEnvMapping.resolvePixiEnvDirectory(m_pixiLockContent);

    }

    /**
     * Get a PythonProcessProvider that can be used to execute Python in this environment. The returned provider will
     * only work if the environment is already installed. It does not trigger installation itself. Use
     * {@link #installPythonEnvironment} to ensure the environment is installed before calling this method.
     *
     * @return a PixiPythonCommand for this environment
     * @throws IOException if the environment cannot be installed
     */
    public ExternalProcessProvider getPythonCommand() throws IOException {
        final Path envPath = getPixiEnvironmentPath();
        final Path tomlPath = envPath.resolve("pixi.toml");
        LOGGER.debug("Creating PixiPythonCommand: envPath=" + envPath + ", tomlPath=" + tomlPath + ", tomlExists="
            + Files.exists(tomlPath) + ", tomlAbsolute=" + tomlPath.toAbsolutePath().normalize());
        return new PixiPythonCommand(tomlPath);
    }

    /**
     * Install the Pixi environment represented by this port object if it is not already installed, with progress
     * reporting. This method is thread-safe and cancelable. If multiple threads call this method concurrently (even
     * across different port object instances targeting the same directory), only one will perform the installation
     * while others wait for it to complete. All threads will receive the same result (success or exception).
     *
     * @param exec execution monitor for cancellation support.
     * @throws IOException if installation fails
     * @throws CanceledExecutionException if the operation is canceled via the execution monitor
     */
    public void installPythonEnvironment(final ExecutionMonitor exec) throws IOException, CanceledExecutionException {
        installPythonEnvironment(null, m_pixiTomlContent, m_pixiLockContent, exec);
    }

    /**
     * Package-private for testing. When {@code installDir} is {@code null} the directory is resolved via
     * {@link #getPixiEnvironmentPath()}; tests pass an explicit temporary directory to bypass the OSGi preference
     * store.
     */
    static void installPythonEnvironment(final Path installDir, final String pixiTomlContent,
        final String pixiLockContent, final ExecutionMonitor exec) throws IOException, CanceledExecutionException {
        final Path resolvedInstallDir = installDir != null ? installDir : PixiEnvMapping.resolvePixiEnvDirectory(pixiLockContent);

        final var newFuture = new CompletableFuture<Void>();
        final var existingFuture = GLOBAL_INSTALL_FUTURES.putIfAbsent(installDir, newFuture);

        if (existingFuture == null) {
            // No other thread is installing to this directory — we are the installer.
            try {
                performInstallation(exec, installDir, pixiTomlContent, pixiLockContent);
                newFuture.complete(null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("Installation thread was interrupted.");
                newFuture.cancel(false);
                throw new CanceledExecutionException("Python environment installation was canceled.");
            } catch (IOException e) {
                // Mark future as failed AND re-throw so the caller is aware that installation did not succeed.
                System.out.println("Installation failed with IOException: " + e.getMessage());
                newFuture.completeExceptionally(e);
                throw e;
            } catch (Exception e) {
                // Wrap and re-throw non-IOException failures.
                System.out.println("Installation failed with Exception: " + e.getMessage());
                final var ioEx = new IOException("Python environment installation failed: " + e.getMessage(), e);
                newFuture.completeExceptionally(ioEx);
                throw ioEx;
            } finally {
                // Always remove the future once done (success, failure, or cancellation) to prevent stale entries
                // from short-circuiting future independent executions.
                System.out.println("Cleaning up installation future for directory: " + installDir);
                GLOBAL_INSTALL_FUTURES.remove(installDir, newFuture);
            }
        } else {
            // Another thread is already installing to this directory — wait for it.
            try {
                existingFuture.join();
            } catch (CancellationException e) {
                throw new CanceledExecutionException("Python environment installation was canceled.");
            } catch (CompletionException e) {
                // Unwrap and re-throw as IOException rather than letting the unchecked CompletionException escape.
                final Throwable cause = e.getCause();
                if (cause instanceof IOException ioe) {
                    throw ioe;
                }
                throw new IOException(
                    "Python environment installation failed: " + (cause != null ? cause.getMessage() : e.getMessage()),
                    cause);
            }
        }
    }

    /**
     * Performs the actual Pixi environment installation. This method is only called by one thread.
     *
     * @param exec execution monitor for progress reporting and cancellation
     * @param installDir the directory where the Pixi environment will be installed
     * @param pixiTomlContent the content of the pixi.toml file
     * @param pixiLockContent the content of the pixi.lock file
     * @throws IOException if installation fails
     * @throws InterruptedException if the installation is interrupted
     */
    private static void performInstallation(final ExecutionMonitor exec, final Path installDir,
        final String pixiTomlContent, final String pixiLockContent) throws IOException, InterruptedException {

        // Double-check if environment is already installed (could happen with existing
        // path)
        final Path envDir = installDir.resolve(".pixi").resolve("envs").resolve("default");
        if (Files.exists(envDir)) {
            LOGGER.debug("Pixi environment already installed at: " + envDir);
            exec.setProgress(1.0, "Environment already installed");
            return;
        }

        LOGGER.info("Installing Pixi environment at: " + installDir);
        exec.setProgress(0.0, "Preparing environment installation...");

        // Create project directory if needed
        Files.createDirectories(installDir);

        // Write pixi.toml
        exec.setProgress(0.1, "Writing environment configuration...");
        final Path pixiTomlPath = installDir.resolve("pixi.toml");
        Files.writeString(pixiTomlPath, pixiTomlContent, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);

        // Write pixi.lock
        final Path pixiLockPath = installDir.resolve("pixi.lock");
        Files.writeString(pixiLockPath, pixiLockContent, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);

        // Run pixi install to create the environment
        exec.setProgress(0.2, "Installing Python environment (this may take a minute)...");
        try {
            final Path pixiHome = installDir.resolve(".pixi-home");
            Files.createDirectories(pixiHome);
            final Map<String, String> extraEnv = Map.of("PIXI_HOME", pixiHome.toString());
            final String[] pixiArgs = {"install", "--color", "never", "--no-progress"};

            BooleanSupplier cancellationCallback = () -> {
                if (exec != null) {
                    try {
                        exec.checkCanceled();
                        return false;
                    } catch (CanceledExecutionException e) {
                        return true;
                    }
                }
                return false;
            };
            final var callResult =
                PixiBinary.callPixiWithCancellation(installDir, extraEnv, cancellationCallback, pixiArgs);

            if (callResult.returnCode() != 0) {
                final String errorDetails = PixiUtils.getMessageFromCallResult(callResult);
                throw new IOException("Pixi install failed: " + errorDetails);
            }

            exec.setProgress(1.0, "Environment installation complete");
        } catch (PixiBinaryLocationException ex) {
            throw new IOException("Could not locate Pixi binary to install environment.", ex);
        }
    }

    @Override
    public PythonEnvironmentPortObjectSpec getSpec() {
        return PythonEnvironmentPortObjectSpec.INSTANCE;
    }

    @Override
    public JComponent[] getViews() {
        return new JComponent[]{};
    }

    @Override
    protected void save(final ModelContentWO model, final ExecutionMonitor exec) throws CanceledExecutionException {
        model.addString(CFG_PIXI_TOML_CONTENT, m_pixiTomlContent);
        model.addString(CFG_PIXI_LOCK_CONTENT, m_pixiLockContent);
    }

    @Override
    protected void load(final ModelContentRO model, final PortObjectSpec spec, final ExecutionMonitor exec)
        throws InvalidSettingsException, CanceledExecutionException {
        m_pixiTomlContent = model.getString(CFG_PIXI_TOML_CONTENT);
        m_pixiLockContent = model.getString(CFG_PIXI_LOCK_CONTENT);
    }

    @Override
    public String getSummary() {
        try {
            final Path envPath = getPixiEnvironmentPath();
            return "Pixi Environment: " + envPath.toString();
        } catch (IOException e) {
            return "Pixi Environment (path unavailable)";
        }
    }

    /**
     * Checks if the Pixi environment represented by this port object is already installed on disk.
     *
     * @return true if the Pixi environment represented by this port object is already installed on disk, false
     *         otherwise.
     */
    public boolean isEnvironmentInstalled() {
        Path installDir;
        try {
            installDir = getPixiEnvironmentPath();
        } catch (IOException ex) {
            return false;
        }
        Path envDir = installDir.resolve(".pixi").resolve("envs").resolve("default");
        return Files.exists(envDir);
    }

}
