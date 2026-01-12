package org.knime.pixi.nodes;

import org.knime.core.webui.node.dialog.defaultdialog.internal.file.FileReaderWidget;
import org.knime.core.webui.node.dialog.defaultdialog.internal.file.FileSelection;
import org.knime.core.webui.node.dialog.defaultdialog.internal.file.FileSelectionWidget;
import org.knime.core.webui.node.dialog.defaultdialog.internal.file.SingleFileSelectionMode;
import org.knime.node.parameters.NodeParameters;
import org.knime.node.parameters.Widget;
import org.knime.node.parameters.layout.Layout;

/**
 * Node Parameters for the Pixi TOML Environment Reader node
 *
 * @author Marc Lehner, KNIME GmbH, Konstanz, Germany
 * @since 5.10
 */
@SuppressWarnings("restriction")
final class PixiTomlEnvironmentReaderNodeParameters implements NodeParameters {

    static final class SingleFileSelectionSection {
    }

    @Widget(title = "pixi.toml file", description = "Select the pixi.toml file to read the environment specification from.")
    @FileSelectionWidget(value = SingleFileSelectionMode.FILE)
    @FileReaderWidget(fileExtensions = {"toml"})
    @Layout(SingleFileSelectionSection.class)
    FileSelection m_tomlFile = new FileSelection();
}
