package org.knime.pixi.port;


import java.nio.file.Path;

import javax.swing.JComponent;

import org.knime.conda.CondaEnvironmentDirectory;
import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.ExecutionMonitor;
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.ModelContentRO;
import org.knime.core.node.ModelContentWO;
import org.knime.core.node.port.AbstractSimplePortObject;
import org.knime.core.node.port.PortObjectSpec;
import org.knime.core.node.port.PortType;
import org.knime.core.node.port.PortTypeRegistry;

/**
 * Port object containing information about a Pixi environment.
 *
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 * @since 5.10
 */
public final class PixiEnvironmentPortObject extends AbstractSimplePortObject {

    /** The port type for Pixi environment port objects. */
    public static final PortType TYPE = PortTypeRegistry.getInstance().getPortType(PixiEnvironmentPortObject.class);

    /** The port type for optional Pixi environment port objects. */
    public static final PortType TYPE_OPTIONAL =
        PortTypeRegistry.getInstance().getPortType(PixiEnvironmentPortObject.class, true);

    private static final String CFG_PIXI_ENV_PATH = "pixi_environment_path";

    private Path m_pixiEnvironmentPath;

    /**
     * Serializer for {@link PixiEnvironmentPortObject}.
     */
    public static final class Serializer extends AbstractSimplePortObjectSerializer<PixiEnvironmentPortObject> {
    }

    /**
     * Public no-arg constructor for deserialization.
     */
    public PixiEnvironmentPortObject() {
    }

    /**
     * Constructor.
     *
     * @param pythonExecutablePath the path to the Python executable in the Pixi environment
     */
    public PixiEnvironmentPortObject(final Path pythonExecutablePath) {
        m_pixiEnvironmentPath = pythonExecutablePath;
    }

    /**
     * @return the path to the pixi environment on disk
     */
    public Path getPixiEnvironmentPath() {
        return m_pixiEnvironmentPath;
    }

    /**
     * @param baseDir The path to the pixi environment (containing .pixi/envs/default)
     * @param executableName The executable name, usually "python" or "r"
     * @return The path to the executable inside the environment
     */
    private static Path resolveExecutable(final Path baseDir, final String executableName) {
        Path envDir = baseDir.resolve(".pixi").resolve("envs").resolve("default");
        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        return isWin ? envDir.resolve(executableName + ".exe") : envDir.resolve("bin").resolve(executableName);
    }

    /**
     * @return the path to the python executable in the Pixi environment, or "null" if no python executable was found
     */
    public Path getPythonExecutablePath() {
        return resolveExecutable(m_pixiEnvironmentPath, "python");
    }

    /**
     * @return the path to the R executable in the Pixi environment, or "null" if no R executable was found
     */
    public Path getRExecutablePath() {
        return resolveExecutable(m_pixiEnvironmentPath, "r");
    }

    /**
     * Returns a CondaEnvironmentDirectory for this Pixi environment. This allows reusing conda-related
     * infrastructure for pixi environments.
     *
     * @return a CondaEnvironmentDirectory representing this Pixi environment
     */
    public CondaEnvironmentDirectory getAsCondaEnvironmentDirectory() {
        final Path pythonExec = getPythonExecutablePath();
        final Path envDir = pythonExec.getParent();
        final String envDirPath;
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            // On Windows, python.exe is directly in the environment directory
            envDirPath = envDir.toString();
        } else {
            // On Linux/macOS, python is in bin/, so we need the parent of bin/
            envDirPath = envDir.getParent().toString();
        }
        return new CondaEnvironmentDirectory(envDirPath);
    }

    @Override
    protected void save(final ModelContentWO model, final ExecutionMonitor exec) throws CanceledExecutionException {
        model.addString(CFG_PIXI_ENV_PATH, m_pixiEnvironmentPath.toString());
    }

    @Override
    protected void load(final ModelContentRO model, final PortObjectSpec spec, final ExecutionMonitor exec)
        throws InvalidSettingsException, CanceledExecutionException {
        m_pixiEnvironmentPath = Path.of(model.getString(CFG_PIXI_ENV_PATH));
    }

    @Override
    public String getSummary() {
        return "Pixi Environment: " + m_pixiEnvironmentPath;
    }

    @Override
    public PixiEnvironmentPortObjectSpec getSpec() {
        return PixiEnvironmentPortObjectSpec.INSTANCE;
    }

    @Override
    public JComponent[] getViews() {
        return new JComponent[]{};
    }
}
