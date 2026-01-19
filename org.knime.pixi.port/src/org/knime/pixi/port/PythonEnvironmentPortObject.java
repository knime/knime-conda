package org.knime.pixi.port;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import javax.swing.JComponent;

import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.ExecutionMonitor;
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.ModelContentRO;
import org.knime.core.node.ModelContentWO;
import org.knime.core.node.port.AbstractSimplePortObject;
import org.knime.core.node.port.PortObjectSpec;
import org.knime.core.node.port.PortType;
import org.knime.core.node.port.PortTypeRegistry;
import org.knime.conda.envinstall.pixi.PixiBinary;
import org.knime.conda.envinstall.pixi.PixiBinary.PixiBinaryLocationException;

/**
 * Port object containing information about a Pixi environment.
 *
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 * @since 5.10
 */
public final class PythonEnvironmentPortObject extends AbstractSimplePortObject {

    /** The port type for Pixi environment port objects. */
    public static final PortType TYPE = PortTypeRegistry.getInstance().getPortType(PythonEnvironmentPortObject.class);

    /** The port type for optional Pixi environment port objects. */
    public static final PortType TYPE_OPTIONAL =
        PortTypeRegistry.getInstance().getPortType(PythonEnvironmentPortObject.class, true);

    private static final String CFG_PIXI_ENV_PATH = "pixi_environment_path";
    private static final String CFG_PIXI_TOML_CONTENT = "pixi_toml_content";
    private static final String CFG_PIXI_LOCK_CONTENT = "pixi_lock_content";
    
    
    private String m_pixiTomlContent;
    
    private String m_pixiLockContent;

    private Path m_pixiEnvironmentPath;

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
        System.out.println("Created PythonEnvironmentPortObject without predefined path.");
	}
    
    public PythonEnvironmentPortObject(String pixiTomlContent, String pixiLockContent, Path pixiEnvironmentPath) {
    	m_pixiTomlContent = pixiTomlContent;
		m_pixiLockContent = pixiLockContent;
		m_pixiEnvironmentPath = pixiEnvironmentPath;
        System.out.println("Created PythonEnvironmentPortObject with predefined path: " + pixiEnvironmentPath);
    }

    /**
     * @return the path to the pixi project directory on disk (containing pixi.toml and .pixi/)
     * @throws IOException if the path cannot be resolved
     */
    public Path getPixiEnvironmentPath() throws IOException {
        if (m_pixiEnvironmentPath != null) {
            return m_pixiEnvironmentPath;
        } else {
            return resolveProjectDirectory(m_pixiTomlContent, null);
        }
    }
    
    /**
     * Get a PythonCommand that can be used to execute Python in this environment.
     * This method ensures the environment is installed before returning the command.
     *
     * @return a PixiPythonCommand for this environment
     * @throws IOException if the environment cannot be installed
     */
    public PythonCommand getPythonCommand() throws IOException {
        installPixiEnvironment();
        final Path tomlPath = getPixiEnvironmentPath().resolve("pixi.toml");
        System.out.println("Creating PixiPythonCommand with toml path: " + tomlPath);
        return new PixiPythonCommand(tomlPath, "default");
    }
    
    /**
     * Install the Pixi environment represented by this port object if it is not already installed.
     * 
     * @throws IOException if installation fails
     */
    public void installPixiEnvironment() throws IOException {
        final Path installDir = getPixiEnvironmentPath();
        
        // Check if environment is already installed
        final Path envDir = installDir.resolve(".pixi").resolve("envs").resolve("default");
        if (Files.exists(envDir)) {
            // Environment already exists, no need to install
            System.out.println("Pixi environment already installed at: " + envDir);
            return;
        }
        
        System.out.println("Installing Pixi environment at: " + installDir);
        // Create project directory if needed
        Files.createDirectories(installDir);
        
        // Write pixi.toml
        final Path pixiTomlPath = installDir.resolve("pixi.toml");
        Files.writeString(pixiTomlPath, m_pixiTomlContent, StandardCharsets.UTF_8, 
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        
        // Write pixi.lock
        final Path pixiLockPath = installDir.resolve("pixi.lock");
        Files.writeString(pixiLockPath, m_pixiLockContent, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        
        // Run pixi install to create the environment
        try {
            final Path pixiHome = installDir.resolve(".pixi-home");
            Files.createDirectories(pixiHome);
            final Map<String, String> extraEnv = Map.of("PIXI_HOME", pixiHome.toString());
            final String[] pixiArgs = {"install", "--color", "never", "--no-progress"};
            
            final var callResult = PixiBinary.callPixi(installDir, extraEnv, pixiArgs);
            
            if (callResult.returnCode() != 0) {
                final String errorDetails = getMessageFromCallResult(callResult);
                throw new IOException("Pixi install failed: " + errorDetails);
            }
        } catch (PixiBinaryLocationException ex) {
            throw new IOException("Could not locate Pixi binary to install environment.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Pixi installation was interrupted.", ex);
        }
    }
    
    private static Path resolveProjectDirectory(final String manifestText, final Path tomlFilePath) throws IOException {
        // If a file path is provided, use that file's parent directory
        if (tomlFilePath != null) {
            if (!Files.exists(tomlFilePath)) {
                throw new IOException("Provided pixi.toml file does not exist: " + tomlFilePath);
            }
            if (!Files.isRegularFile(tomlFilePath)) {
                throw new IOException("Provided pixi.toml path is not a file: " + tomlFilePath);
            }
            return tomlFilePath.getParent();
        }

        // Otherwise, use the preference-based directory
        if (manifestText == null || manifestText.isBlank()) {
            throw new IOException("Manifest content is empty");
        }

        final Path projectDir = PixiEnvMapping.resolvePixiEnvDirectory(manifestText);
        Files.createDirectories(projectDir);

        final Path manifestPath = projectDir.resolve("pixi.toml");
        if (!Files.exists(manifestPath)) {
            Files.writeString(manifestPath, manifestText, StandardCharsets.UTF_8);
        }
        return projectDir;
    }
    
    private static String getMessageFromCallResult(final PixiBinary.CallResult callResult) {
        final String stdout = callResult.stdout() == null ? "" : callResult.stdout();
        final String stderr = callResult.stderr() == null ? "" : callResult.stderr();

        return "pixi install failed (exit code " + callResult.returnCode() + ").\n"
            + (stderr.isBlank() ? "" : "---- stderr ----\n" + stderr + "\n")
            + (stdout.isBlank() ? "" : "---- stdout ----\n" + stdout + "\n");
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
            return "Pixi Environment: " + getPixiEnvironmentPath();
        } catch (IOException e) {
            return "Pixi Environment (path unavailable)";
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

}
