package org.knime.python3.nodes.testing.pixi;

import javax.swing.JComponent;

import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.ModelContentRO;
import org.knime.core.node.ModelContentWO;
import org.knime.core.node.port.AbstractSimplePortObjectSpec;

/**
 * Port object spec for Pixi environment information.
 *
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 * @since 5.10
 */
public final class PixiEnvironmentPortObjectSpec extends AbstractSimplePortObjectSpec {

    /** Empty spec instance used during configuration. */
    public static final PixiEnvironmentPortObjectSpec INSTANCE = new PixiEnvironmentPortObjectSpec();

    /**
     * Serializer for {@link PixiEnvironmentPortObjectSpec}.
     */
    public static final class Serializer extends AbstractSimplePortObjectSpecSerializer<PixiEnvironmentPortObjectSpec> {
    }

    /**
     * Public no-arg constructor for deserialization.
     */
    public PixiEnvironmentPortObjectSpec() {
    }

    @Override
    protected void save(final ModelContentWO model) {
        // No additional data to save in the spec
    }

    @Override
    protected void load(final ModelContentRO model) throws InvalidSettingsException {
        // No additional data to load in the spec
    }

    @Override
    public JComponent[] getViews() {
        return new JComponent[]{};
    }
}
