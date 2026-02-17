package org.knime.pixi.port;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

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

	/** The port type for optional Python environment port objects. */
	public static final PortType TYPE_OPTIONAL = PortTypeRegistry.getInstance()
			.getPortType(PythonEnvironmentPortObject.class, true);

	private static final NodeLogger LOGGER = NodeLogger.getLogger(PythonEnvironmentPortObject.class);

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
	 * @return the path to the pixi project directory on disk (containing pixi.toml
	 *         and .pixi/)
	 * @throws IOException if the path cannot be resolved
	 */
	private Path getPixiEnvironmentPath() throws IOException {
        return PixiUtils.resolveProjectDirectory(m_pixiLockContent);
	}

	/**
	 * Get a PythonProcessProvider that can be used to execute Python in this
	 * environment. The returned provider will only work if the environment is
	 * already installed. It does not trigger installation itself. Use
	 * {@link #installPythonEnvironment} to ensure the environment is installed
	 * before calling this method.
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
	 * Install the Pixi environment represented by this port object if it is not
	 * already installed, with progress reporting. This method is thread-safe and
	 * cancelable. If multiple threads call this method concurrently (even across
	 * different port object instances targeting the same directory), only one will
	 * perform the installation while others wait for it to complete. All threads
	 * will receive the same result (success or exception).
	 *
	 * @param exec execution monitor for cancellation support.
	 * @throws IOException                if installation fails
	 * @throws CanceledExecutionException if the operation is canceled via the
	 *                                    execution monitor
	 */
	public void installPythonEnvironment(final ExecutionMonitor exec) throws IOException, CanceledExecutionException {

		// Get the installation directory - this is the global synchronization point
		final Path installDir = getPixiEnvironmentPath();

		var future = GLOBAL_INSTALL_FUTURES.putIfAbsent(installDir, new CompletableFuture<>());
		if (future == null) {
			// There was no future with this key -> we need to perform the installation
			future = GLOBAL_INSTALL_FUTURES.get(installDir);
			try {
				performInstallation(exec, installDir);
				future.complete(null);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				future.cancel(false);
				throw new CanceledExecutionException("Python environment installation was canceled.");
			} catch (Exception e) {
				future.completeExceptionally(e);
			}
		} else {
			future.join();
		}
	}

	/**
	 * Performs the actual Pixi environment installation. This method is only called
	 * by one thread.
	 *
	 * @param progressReporter Progress reporter for installation feedback
	 * @throws IOException          if installation fails
	 * @throws InterruptedException
	 */
	// TODO make this static and pass in all needed data as parameters
	private void performInstallation(final ExecutionMonitor exec, final Path installDir)
			throws IOException, InterruptedException {

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
		Files.writeString(pixiTomlPath, m_pixiTomlContent, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING);

		// Write pixi.lock
		final Path pixiLockPath = installDir.resolve("pixi.lock");
		Files.writeString(pixiLockPath, m_pixiLockContent, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING);

		// Run pixi install to create the environment
		exec.setProgress(0.2, "Installing Python environment (this may take a minute)...");
		try {
			final Path pixiHome = installDir.resolve(".pixi-home");
			Files.createDirectories(pixiHome);
			final Map<String, String> extraEnv = Map.of("PIXI_HOME", pixiHome.toString());
			final String[] pixiArgs = { "install", "--color", "never", "--no-progress" };

			// TODO update the PixiBinary API to make a cancelable execution simpler here
			final var callResult = PixiBinary.callPixiWithCancellation(installDir, extraEnv, () -> {
				try {
					exec.checkCanceled();
					return false;
				} catch (CanceledExecutionException e) {
					return true;
				}
			}, pixiArgs);

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
		return new JComponent[] {};
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
