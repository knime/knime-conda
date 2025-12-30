package org.knime.python3.nodes.testing.pixi;

import org.knime.node.parameters.NodeParameters;
import org.knime.node.parameters.Widget;
import org.knime.node.parameters.layout.After;
import org.knime.node.parameters.layout.Layout;
import org.knime.node.parameters.layout.Section;
import org.knime.node.parameters.widget.text.TextAreaWidget;

public final class PixiEnvironmentCreatorNodeParameters implements NodeParameters {

    @Section(title = "Environment Setup")
    interface SetupSection {

    }

    @Section(title = "Output")
    @After(SetupSection.class)
    interface OutputSection {

    }


    @Widget(title = "pixi.toml", description = "Content of your pixi toml")
    @Layout(SetupSection.class)
    @TextAreaWidget(rows = 20)
    String m_pixiTomlContent = "";

    @Widget(title = "pixi output", description = "Content of your pixi execution")
    @Layout(OutputSection.class)
    @TextAreaWidget(rows = 20)
    String m_pixiOutputContent = "";
}