package org.knime.pixi.port;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JComponent;

import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.ExecutionMonitor;
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.NodeLogger;
import org.knime.core.node.context.ports.PortsConfiguration;
import org.knime.core.node.ModelContentRO;
import org.knime.core.node.ModelContentWO;
import org.knime.core.node.port.AbstractSimplePortObject;
import org.knime.core.node.port.PortObject;
import org.knime.core.node.port.PortObjectSpec;
import org.knime.core.node.port.PortType;
import org.knime.core.node.port.PortTypeRegistry;
import org.knime.conda.envinstall.pixi.PixiBinary;
import org.knime.conda.envinstall.pixi.PixiBinary.PixiBinaryLocationException;
import org.knime.python3.processprovider.PythonProcessProvider;

/**
 * Port object containing information about a Python environment.
 *
 * @author Marc Lehner, KNIME GmbH, Zurich, Switzerland
 * @since 5.10
 */
public final class PythonEnvironmentPortObject extends AbstractSimplePortObject {

	/** The port type for Python environment port objects. */
	public static final PortType TYPE = PortTypeRegistry.getInstance().getPortType(PythonEnvironmentPortObject.class);

	/** The port type for optional Python environment port objects. */
	public static final PortType TYPE_OPTIONAL = PortTypeRegistry.getInstance()
			.getPortType(PythonEnvironmentPortObject.class, true);

	private static final NodeLogger LOGGER = NodeLogger.getLogger(PythonEnvironmentPortObject.class);

	private static final String CFG_PIXI_ENV_PATH = "pixi_environment_path";
	private static final String CFG_PIXI_TOML_CONTENT = "pixi_toml_content";
	private static final String CFG_PIXI_LOCK_CONTENT = "pixi_lock_content";

	/**
	 * Global map of installation futures keyed by installation directory path. This
	 * ensures that concurrent installations to the same directory are serialized,
	 * as Pixi does not support multiple concurrent installations to the same
	 * location.
	 */
	private static final ConcurrentHashMap<Path, CompletableFuture<Void>> GLOBAL_INSTALL_FUTURES = new ConcurrentHashMap<>();

	private String m_pixiTomlContent;

	private String m_pixiLockContent;

	private Path m_pixiEnvironmentPath;

	/**
	 * Future tracking the installation operation for this specific port object
	 * instance. This ensures thread-safe, single installation even when multiple
	 * threads call installPixiEnvironment() on the same port object concurrently. -
	 * null: Installation not started - non-null: Installation in progress or
	 * completed
	 */
	private final AtomicReference<CompletableFuture<Void>> m_installFuture = new AtomicReference<>();

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
	public PythonEnvironmentPortObject(String pixiTomlContent, String pixiLockContent) {
		m_pixiTomlContent = pixiTomlContent;
		m_pixiLockContent = pixiLockContent;
		LOGGER.debug("Created PythonEnvironmentPortObject without predefined path.");
	}

	public PythonEnvironmentPortObject(String pixiTomlContent, String pixiLockContent, Path pixiEnvironmentPath) {
		m_pixiTomlContent = pixiTomlContent;
		m_pixiLockContent = pixiLockContent;
		m_pixiEnvironmentPath = pixiEnvironmentPath;
		LOGGER.debug("Created PythonEnvironmentPortObject with predefined path: " + pixiEnvironmentPath);
	}

	/**
	 * @return the path to the pixi project directory on disk (containing pixi.toml
	 *         and .pixi/)
	 * @throws IOException if the path cannot be resolved
	 */
	public Path getPixiEnvironmentPath() throws IOException {
		if (m_pixiEnvironmentPath != null) {
			// Custom path provided - normalize it to remove .. segments
			final Path normalizedPath = m_pixiEnvironmentPath.toAbsolutePath().normalize();

			// Check if it exists and has installed environment
			LOGGER.debug("Checking custom path: " + normalizedPath);
			LOGGER.debug("Path exists: " + Files.exists(normalizedPath));

			final Path envDir = normalizedPath.resolve(".pixi").resolve("envs").resolve("default");
			LOGGER.debug("Expected env dir: " + envDir);
			LOGGER.debug("Env dir exists: " + Files.exists(envDir));

			if (Files.exists(normalizedPath) && Files.exists(envDir)) {
				LOGGER.debug("Using custom environment path (exists and installed): " + normalizedPath);
				return normalizedPath;
			} else {
				// Path doesn't exist or environment not installed - fall back to hash-based
				// directory
				LOGGER.debug(
						"Custom path not available (" + normalizedPath + "), falling back to hash-based directory");
				// Don't cache this fallback path - use the hash-based one
				return PixiUtils.resolveProjectDirectory(m_pixiTomlContent, null);
			}
		} else {
			return PixiUtils.resolveProjectDirectory(m_pixiTomlContent, null);
		}
	}

	/**
	 * Get a PythonProcessProvider that can be used to execute Python in this environment.
	 * This method ensures the environment is installed before returning the
	 * command.
	 *
	 * @return a PixiPythonCommand for this environment
	 * @throws IOException if the environment cannot be installed
	 */
	public PythonProcessProvider getPythonCommand() throws IOException {
		try {
			installPixiEnvironment(null, PixiInstallationProgressReporter.NoOpProgressReporter.INSTANCE);
		} catch (CanceledExecutionException ex) {
			// Should not happen with null ExecutionMonitor, but handle gracefully
			throw new IOException("Environment installation was canceled.", ex);
		}
		final Path envPath = getPixiEnvironmentPath();
		final Path tomlPath = envPath.resolve("pixi.toml");
		LOGGER.debug("Creating PixiPythonCommand: envPath=" + envPath + ", tomlPath=" + tomlPath + ", tomlExists="
				+ Files.exists(tomlPath) + ", tomlAbsolute=" + tomlPath.toAbsolutePath().normalize());
		return new PixiPythonCommand(tomlPath, "default");
	}
	
	/**
	 * Check if a PortObject is a PythonEnvironmentPortObject.
	 *
	 * @param portObject the port object to check
	 * @return true if the port object is a PythonEnvironmentPortObject
	 */
	public static boolean isPythonEnvironmentPortObject(final PortObject portObject) {
		return portObject instanceof PythonEnvironmentPortObject;
	}

	/**
	 * Extract the Python command from a PythonEnvironmentPortObject.
	 *
	 * @param portObject the port object (may be null if optional port is not connected)
	 * @return the Python command, or null if the port is not connected or not a PythonEnvironmentPortObject
	 * @throws IOException if the Python command cannot be obtained from the environment
	 */
	public static PythonProcessProvider extractPythonCommand(final PortObject portObject) throws IOException {
		if (portObject == null || !isPythonEnvironmentPortObject(portObject)) {
			return null;
		}
		return ((PythonEnvironmentPortObject) portObject).getPythonCommand();
	}

	/**
     * Install the Python environment port if present, with progress reporting.
     * This should be called early in node execution to avoid installation timeout issues.
     * Installation is thread-safe and will only happen once even if called multiple times.
     *
     * @param config the ports configuration
     * @param inObjects the input port objects
     * @param exec the execution monitor for progress reporting and cancellation
     * @throws IOException if installation fails
     * @throws CanceledExecutionException if the operation is canceled
     */
    public static void installPythonEnvironmentWithProgress(
            final PortsConfiguration config, final PortObject[] inObjects, final ExecutionMonitor exec)
            throws IOException, CanceledExecutionException {
            // Find the Python environment port in the input objects
            final PortType[] inTypes = config.getInputPorts();
            PythonEnvironmentPortObject envPort = null;
            for (int i = 0; i < inTypes.length && i < inObjects.length; i++) {
                if (inObjects[i] instanceof PythonEnvironmentPortObject) {
                    envPort = (PythonEnvironmentPortObject) inObjects[i];
                    break;
                }
            }
            
            // If found, install the environment
            if (envPort != null) {
                exec.setMessage("Installing Python environment...");
                // Create simulated progress reporter that maps internal progress to node progress
                final PixiInstallationProgressReporter progressReporter = new PixiInstallationProgressReporter() {
                    @Override
                    public void setProgress(final double fraction, final String message) {
                        exec.setProgress(fraction, message);
                    }

                    @Override
                    public void checkCanceled() throws CanceledExecutionException {
                        exec.checkCanceled();
                    }
                };
                
                // Use simulated progress since we don't yet capture pixi output
                envPort.installPixiEnvironment(exec, 
                    PixiInstallationProgressReporter.createSimulated(progressReporter));
            }
    }

	/**
	 * Install the Pixi environment represented by this port object if it is not
	 * already installed, with progress reporting. This method is thread-safe and
	 * cancelable. If multiple threads call this method concurrently (even across
	 * different port object instances targeting the same directory), only one will
	 * perform the installation while others wait for it to complete. All threads
	 * will receive the same result (success or exception).
	 * 
	 * @param exec             Optional execution monitor for cancellation support.
	 *                         Pass null if cancellation is not needed (e.g., when
	 *                         called from non-node context).
	 * @param progressReporter Progress reporter for installation feedback. Pass
	 *                         NoOpProgressReporter.INSTANCE if progress reporting
	 *                         is not needed.
	 * @throws IOException                if installation fails
	 * @throws CanceledExecutionException if the operation is canceled via the
	 *                                    execution monitor
	 */
	public void installPixiEnvironment(final ExecutionMonitor exec,
			final PixiInstallationProgressReporter progressReporter) throws IOException, CanceledExecutionException {

		// Fast path: Check if this port object's installation is already complete
		CompletableFuture<Void> future = m_installFuture.get();
		if (future != null && future.isDone() && !future.isCompletedExceptionally()) {
			// Installation already completed successfully for this port object
			return;
		}

		// Get the installation directory - this is the global synchronization point
		final Path installDir = getPixiEnvironmentPath();

		// Check global installation state for this directory
		CompletableFuture<Void> globalFuture = GLOBAL_INSTALL_FUTURES.get(installDir);
		if (globalFuture != null && globalFuture.isDone() && !globalFuture.isCompletedExceptionally()) {
			// Installation already completed successfully in another port object
			// Mark this port object as installed too
			m_installFuture.compareAndSet(null, globalFuture);
			return;
		}

		// Attempt to start installation for this port object
		if (future == null) {
			final CompletableFuture<Void> newFuture = new CompletableFuture<>();
			if (m_installFuture.compareAndSet(null, newFuture)) {
				// This thread won the race for THIS port object
				// Now try to win the global race for the installation directory
				final CompletableFuture<Void> newGlobalFuture = new CompletableFuture<>();
				final CompletableFuture<Void> existingGlobalFuture = 
					GLOBAL_INSTALL_FUTURES.putIfAbsent(installDir, newGlobalFuture);

				if (existingGlobalFuture == null) {
					// This thread won both races - perform the installation
					try {
						LOGGER.debug("Thread " + Thread.currentThread().getName() + 
							" starting installation to " + installDir);
						// Respect cancellation before starting the actual installation
						exec.checkCanceled();
						progressReporter.checkCanceled();
						performInstallation(progressReporter);
						newGlobalFuture.complete(null);
						newFuture.complete(null);
						LOGGER.debug("Installation completed successfully by thread: " + 
							Thread.currentThread().getName());
					} catch (Exception ex) {
						newGlobalFuture.completeExceptionally(ex);
						newFuture.completeExceptionally(ex);
						// Remove from global map on failure so retry is possible
						GLOBAL_INSTALL_FUTURES.remove(installDir, newGlobalFuture);
						LOGGER.error("Installation failed in thread: " + 
							Thread.currentThread().getName(), ex);
						// Re-throw so this thread sees the exception immediately
						if (ex instanceof IOException) {
							throw (IOException) ex;
						} else {
							throw new IOException("Pixi installation failed.", ex);
						}
					}
					return;
				} else {
					// Another thread is installing to the same directory - wait for them
					LOGGER.debug("Thread " + Thread.currentThread().getName() + 
						" waiting for global installation to " + installDir);
					future = existingGlobalFuture;
					// Update this port object's future to point to the global one
					m_installFuture.set(existingGlobalFuture);
				}
			} else {
				// Another thread won the race for this port object - get their future
				future = m_installFuture.get();
			}
		}

		// Wait for the installation to complete with cancellation support
		waitForInstallation(future, exec);
	}

	/**
	 * Performs the actual Pixi environment installation. This method is only called
	 * by one thread.
	 * 
	 * @param progressReporter Progress reporter for installation feedback
	 * @throws IOException if installation fails
	 */
	private void performInstallation(final PixiInstallationProgressReporter progressReporter) throws IOException {
		final Path installDir = getPixiEnvironmentPath();

		// Double-check if environment is already installed (could happen with existing
		// path)
		final Path envDir = installDir.resolve(".pixi").resolve("envs").resolve("default");
		if (Files.exists(envDir)) {
			LOGGER.debug("Pixi environment already installed at: " + envDir);
			progressReporter.setProgress(1.0, "Environment already installed");
			return;
		}

		LOGGER.info("Installing Pixi environment at: " + installDir);
		progressReporter.setProgress(0.0, "Preparing environment installation...");

		// Create project directory if needed
		Files.createDirectories(installDir);

		// Write pixi.toml
		progressReporter.setProgress(0.1, "Writing environment configuration...");
		final Path pixiTomlPath = installDir.resolve("pixi.toml");
		Files.writeString(pixiTomlPath, m_pixiTomlContent, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING);

		// Write pixi.lock
		final Path pixiLockPath = installDir.resolve("pixi.lock");
		Files.writeString(pixiLockPath, m_pixiLockContent, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING);

		// Run pixi install to create the environment
		progressReporter.setProgress(0.2, "Installing Python environment (this may take a minute)...");
		try {
			final Path pixiHome = installDir.resolve(".pixi-home");
			Files.createDirectories(pixiHome);
			final Map<String, String> extraEnv = Map.of("PIXI_HOME", pixiHome.toString());
			final String[] pixiArgs = { "install", "--color", "never", "--no-progress" };

			final var callResult = PixiBinary.callPixi(installDir, extraEnv, pixiArgs);

			if (callResult.returnCode() != 0) {
				final String errorDetails = getMessageFromCallResult(callResult);
				throw new IOException("Pixi install failed: " + errorDetails);
			}

			progressReporter.setProgress(1.0, "Environment installation complete");
		} catch (PixiBinaryLocationException ex) {
			throw new IOException("Could not locate Pixi binary to install environment.", ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("Pixi installation was interrupted.", ex);
		}
	}

	/**
	 * Waits for the installation future to complete, with support for cancellation.
	 * 
	 * @param future The future representing the installation operation
	 * @param exec   Optional execution monitor for cancellation checks
	 * @throws IOException                if installation failed
	 * @throws CanceledExecutionException if canceled via execution monitor
	 */
	private void waitForInstallation(final CompletableFuture<Void> future, final ExecutionMonitor exec)
			throws IOException, CanceledExecutionException {

		if (future == null) {
			return; // No installation in progress
		}

		LOGGER.debug("Thread " + Thread.currentThread().getName() + " waiting for installation to complete");

		// Poll the future with periodic cancellation checks
		while (!future.isDone()) {
			// Check for cancellation if execution monitor provided
			if (exec != null) {
				try {
					exec.checkCanceled();
				} catch (CanceledExecutionException ex) {
					LOGGER.info("Installation wait canceled for thread: " + Thread.currentThread().getName());
					throw ex;
				}
			}

			// Sleep briefly before checking again
			try {
				Thread.sleep(100); // Check every 100ms
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IOException("Wait for installation was interrupted.", ex);
			}
		}

		// Installation completed - check if it succeeded or failed
		try {
			future.get(); // This will throw if installation failed
			LOGGER.debug("Thread " + Thread.currentThread().getName() + " finished waiting - installation succeeded");
		} catch (ExecutionException ex) {
			// Installation failed - propagate the exception
			final Throwable cause = ex.getCause();
			if (cause instanceof IOException) {
				throw (IOException) cause;
			} else {
				throw new IOException("Pixi installation failed.", cause);
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("Wait for installation was interrupted.", ex);
		}
	}

	private static String getMessageFromCallResult(final PixiBinary.CallResult callResult) {
		return PixiUtils.getMessageFromCallResult(callResult);
	}

	@Override
	public PythonEnvironmentPortObjectSpec getSpec() {
		return PythonEnvironmentPortObjectSpec.INSTANCE;
	}

	@Override
	public JComponent[] getViews() {
		return new JComponent[] {};
	}

	@Override
	protected void save(final ModelContentWO model, final ExecutionMonitor exec) throws CanceledExecutionException {
		model.addString(CFG_PIXI_TOML_CONTENT, m_pixiTomlContent);
		model.addString(CFG_PIXI_LOCK_CONTENT, m_pixiLockContent);
		if (m_pixiEnvironmentPath != null) {
			model.addString(CFG_PIXI_ENV_PATH, m_pixiEnvironmentPath.toString());
		}
	}

	@Override
	protected void load(final ModelContentRO model, final PortObjectSpec spec, final ExecutionMonitor exec)
			throws InvalidSettingsException, CanceledExecutionException {
		m_pixiTomlContent = model.getString(CFG_PIXI_TOML_CONTENT);
		m_pixiLockContent = model.getString(CFG_PIXI_LOCK_CONTENT);
		if (model.containsKey(CFG_PIXI_ENV_PATH)) {
			m_pixiEnvironmentPath = Path.of(model.getString(CFG_PIXI_ENV_PATH));
		}
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

}
