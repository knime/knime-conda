package org.knime.python3.nodes.testing.pixi;

import java.util.List;
import java.util.Optional;

import org.knime.node.parameters.NodeParameters;
import org.knime.node.parameters.Widget;
import org.knime.node.parameters.array.ArrayWidget;
import org.knime.node.parameters.layout.Layout;
import org.knime.node.parameters.layout.Section;
import org.knime.node.parameters.persistence.Persist;
import org.knime.node.parameters.updates.ValueSwitchWidget;
import org.knime.node.parameters.widget.Advanced;
import org.knime.node.parameters.widget.OptionalWidget;
import org.knime.node.parameters.widget.choices.EnumChoicesProvider;
import org.knime.node.parameters.widget.choices.EnumChoice;
import org.knime.node.parameters.widget.choices.Label;
import org.knime.node.parameters.widget.text.TextInputWidget;

/**
 * Parameters for the "Pixi Environment Creator (Labs)" node.
 *
 * The dialog lets users:
 *  - Point to a pixi executable (autodetected if empty)
 *  - Choose a base KNIME Python metapackage
 *  - Add additional packages (with optional version bounds, and installer choice)
 *
 * Persistence keys chosen to be stable & concise. Adjust only if integration requires legacy compatibility.
 */
@Layout({
    @Section(title = "Environment Base", description = "Select or adjust the base KNIME Python environment."),
    @Section(title = "Additional Packages",
             description = "Optional additional packages (conda or pip) with optional version bounds.")
})
public final class PixiEnvironmentCreatorNodeParameters implements NodeParameters {

    // ───────────────────────────────────────────────────────────────────── Enums ──

    public enum BaseEnvironment {
        @Label("Minimal KNIME Python base environment")
        BASE("knime-python-base"),
        @Label("Full KNIME Python scripting stack")
        SCRIPTING("knime-python-scripting");

        private final String m_pkg;

        BaseEnvironment(final String pkg) {
            m_pkg = pkg;
        }

        public String getPackageName() {
            return m_pkg;
        }
    }

    public enum Installer {
        @Label("Install package via conda (default)")
        CONDA("CONDA"),
        @Label("Install package via pip (PyPI)")
        PIP("PIP");

        private final String m_id;

        Installer(final String id) {
            m_id = id;
        }

        public String id() {
            return m_id;
        }
    }

    // ───────────────────────────────────────────────────────────────── Parameter Group ──

    /**
     * One element of the additional packages array.
     */
    public static final class PackageSpecParameters implements NodeParameters {

        @Widget(title = "Package", description = "The package name (conda or PyPI).")
        @Persist(configKey = "name")
        @TextInputWidget
        String m_name = "";

        @Widget(title = "Min version", description = "Optional inclusive lower bound (e.g. 1.2.0).")
        @Persist(configKey = "lowerBound")
        @Advanced
        @TextInputWidget
        String m_lowerBound = "";

        @Widget(title = "Max version", description = "Optional exclusive upper bound (e.g. 2.0).")
        @Persist(configKey = "upperBound")
        @Advanced
        @TextInputWidget
        String m_upperBound = "";

        @Widget(title = "Installer", description = "Choose whether to install via conda or pip.")
        @Persist(configKey = "installer")
        @ValueSwitchWidget
        EnumChoice<Installer> m_installer = EnumChoicesProvider.of(Installer.class).getChoices().get(0);
    }

    // ───────────────────────────────────────────────────────────────────── Widgets ──

    @Widget(title = "Pixi Executable",
        description = "Advanced: Path to the local pixi executable. If empty, the node tries to autodetect.")
    @Persist(configKey = "pixiExecutable")
    @Advanced
    @TextInputWidget(placeholderText = "/path/to/pixi/exe")
    String m_pixiExecutable = "";

    @Widget(title = "Base environment",
        description = "Select the KNIME metapackage serving as a base.")
    @Persist(configKey = "baseEnvironment")
    @ValueSwitchWidget
    EnumChoice<BaseEnvironment> m_baseEnvironment =
        EnumChoicesProvider.of(BaseEnvironment.class).getChoice(BaseEnvironment.SCRIPTING);

    @Widget(title = "Additional packages",
        description = "Optional list of additional packages with version bounds and installer.")
    @Persist(configKey = "additionalPackages")
    @ArrayWidget(elementTitle = "Package", buttonText = "Add new package")
    PackageSpecParameters[] m_additionalPackages = new PackageSpecParameters[0];

    // Optional "effective" resolved pixi executable (autodetected). Not user-editable; persisted separately.
    @Widget(title = "Resolved Pixi Executable",
        description = "Autodetected pixi executable actually used. Shown for transparency.")
    @Persist(configKey = "resolvedPixiExecutable")
    @OptionalWidget
    @Advanced
    String m_resolvedPixiExecutable; // left null until configure

    // Manifest preview (helpful during dialog interaction)
    @Widget(title = "Manifest Preview",
        description = "Preview of the manifest that will be generated (read-only).")
    @Persist(configKey = "manifestPreview")
    @OptionalWidget
    @Advanced
    String m_manifestPreview;
}