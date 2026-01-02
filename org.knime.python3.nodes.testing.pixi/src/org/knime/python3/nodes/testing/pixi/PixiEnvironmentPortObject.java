package org.knime.python3.nodes.testing.pixi;

import java.nio.file.Path;

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

/**
 * Port object containing information about a Pixi Python environment.
 *
 * @author KNIME GmbH
 */
public final class PixiEnvironmentPortObject extends AbstractSimplePortObject {

    /** The port type for Pixi environment port objects. */
    public static final PortType TYPE = PortTypeRegistry.getInstance().getPortType(PixiEnvironmentPortObject.class);

    /** The port type for optional Pixi environment port objects. */
    public static final PortType TYPE_OPTIONAL =
        PortTypeRegistry.getInstance().getPortType(PixiEnvironmentPortObject.class, true);

    private static final String CFG_PYTHON_EXECUTABLE_PATH = "python_executable_path";

    private Path m_pythonExecutablePath;

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
        m_pythonExecutablePath = pythonExecutablePath;
    }

    /**
     * @return the path to the Python executable in the Pixi environment
     */
    public Path getPythonExecutablePath() {
        return m_pythonExecutablePath;
    }

    @Override
    protected void save(final ModelContentWO model, final ExecutionMonitor exec) throws CanceledExecutionException {
        model.addString(CFG_PYTHON_EXECUTABLE_PATH, m_pythonExecutablePath.toString());
    }

    @Override
    protected void load(final ModelContentRO model, final PortObjectSpec spec, final ExecutionMonitor exec)
        throws InvalidSettingsException, CanceledExecutionException {
        m_pythonExecutablePath = Path.of(model.getString(CFG_PYTHON_EXECUTABLE_PATH));
    }

    @Override
    public String getSummary() {
        return "Pixi Python Environment: " + m_pythonExecutablePath;
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
