package org.knime.pixi.nodes;

import java.io.StringWriter;
import java.util.Arrays;

import org.knime.core.webui.node.dialog.defaultdialog.internal.button.ButtonWidget;
import org.knime.core.webui.node.dialog.defaultdialog.internal.button.CancelableActionHandler;
import org.knime.node.parameters.NodeParameters;
import org.knime.node.parameters.Widget;
import org.knime.node.parameters.array.ArrayWidget;
import org.knime.node.parameters.updates.ParameterReference;
import org.knime.node.parameters.updates.ValueReference;
import org.knime.node.parameters.widget.choices.Label;
import org.knime.pixi.nodes.PixiUtils.AbstractPixiLockActionHandler;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.io.IndentStyle;
import com.electronwill.nightconfig.toml.TomlWriter;

/**
 * Node Parameters describing the dialog for the Pixi Environment Creator node
 *
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 * @since 5.10
 */
@SuppressWarnings("restriction")
final class PixiEnvironmentCreatorNodeParameters implements NodeParameters {

    @Widget(title = "Packages", description = "Specify the packages to include in the environment")
    @ArrayWidget(elementLayout = ArrayWidget.ElementLayout.HORIZONTAL_SINGLE_LINE, addButtonText = "Add package")
    @ValueReference(PackageArrayRef.class)
    PackageSpec[] m_packages = new PackageSpec[]{
        new PackageSpec("python", PackageSource.CONDA, "3.14", "3.14"),
        new PackageSpec("knime-python-base", PackageSource.CONDA, "5.9", "5.9")
    };

    /**
     * Used by the execute() method to run "pixi install"
     *
     * @return the pixi toml content as string
     */
    String buildPixiToml() {
        return buildTomlFromPackages(m_packages);
    }

    @Widget(title = "Check compatibility",
        description = "Click to check whether this pixi environment can be constructed on all selected operating systems")
    @ButtonWidget(actionHandler = PixiLockActionHandler.class, updateHandler = PixiLockUpdateHandler.class)
    String m_compatibilityCheckButton;

    interface PackageArrayRef extends ParameterReference<PackageSpec[]> {
    }

    static final class PackageSpec implements NodeParameters {

        @Widget(title = "Name", description = "Package name")
        String m_name = "";

        @Widget(title = "Source", description = "Package source (conda or pip)")
        PackageSource m_source = PackageSource.CONDA;

        @Widget(title = "Min version", description = "Minimum version (inclusive, optional)")
        String m_minVersion = "";

        @Widget(title = "Max version", description = "Maximum version (exclusive, optional)")
        String m_maxVersion = "";

        // Default constructor for serialization
        PackageSpec() {
        }

        PackageSpec(final String name, final PackageSource source, final String minVersion, final String maxVersion) {
            m_name = name;
            m_source = source;
            m_minVersion = minVersion;
            m_maxVersion = maxVersion;
        }
    }

    enum PackageSource {
        @Label("Conda")
        CONDA,
        @Label("Pip")
        PIP
    }

    static final class PixiLockActionHandler extends AbstractPixiLockActionHandler<PackagesContent> {
        @Override
        protected String getTomlContent(final PackagesContent dependency) {
            return buildTomlFromPackages(dependency.m_packages);
        }
    }

    static final class PixiLockUpdateHandler extends CancelableActionHandler.UpdateHandler<String, PackagesContent> {
    }

    static final class PackagesContent {
        PackageSpec[] m_packages;
    }

    private static String buildTomlFromPackages(final PackageSpec[] packages) {
        Config config = Config.inMemory();

        // [workspace] section.
        // Note: Need to use CommentedConfig here so that the toml lib contains a section header which pixi needs
        CommentedConfig workspace = CommentedConfig.inMemory();
        workspace.set("channels", Arrays.asList("knime", "conda-forge"));
        workspace.set("platforms", Arrays.asList("win-64", "linux-64", "osx-64", "osx-arm64"));
        config.set("workspace", workspace);

        // [dependencies] section for conda packages
        CommentedConfig dependencies = CommentedConfig.inMemory();
        for (PackageSpec pkg : packages) {
            if (pkg.m_name == null || pkg.m_name.isBlank() || pkg.m_source == PackageSource.PIP) {
                continue;
            }
            dependencies.set(pkg.m_name, formatVersionConstraint(pkg));
        }
        config.set("dependencies", dependencies);

        // [pypi-dependencies] section for pip packages
        CommentedConfig pypiDependencies = CommentedConfig.inMemory();
        boolean hasPipPackages = false;
        for (PackageSpec pkg : packages) {
            if (pkg.m_name != null && !pkg.m_name.isBlank() && pkg.m_source == PackageSource.PIP) {
                pypiDependencies.set(pkg.m_name, formatVersionConstraint(pkg));
                hasPipPackages = true;
            }
        }
        if (hasPipPackages) {
            config.set("pypi-dependencies", pypiDependencies);
        }

        // Write to string
        StringWriter writer = new StringWriter();
        TomlWriter tomlWriter = new TomlWriter();
        tomlWriter.setIndent(IndentStyle.SPACES_2);
        tomlWriter.setWriteTableInlinePredicate(path -> false); // Never write tables inline
        tomlWriter.write(config, writer);
        return writer.toString();
    }

    private static String formatVersionConstraint(final PackageSpec pkg) {
        if (pkg.m_minVersion != null && !pkg.m_minVersion.isBlank()) {
            if (pkg.m_maxVersion != null && !pkg.m_maxVersion.isBlank()) {
                return ">=" + pkg.m_minVersion + ",<" + pkg.m_maxVersion;
            } else {
                return ">=" + pkg.m_minVersion;
            }
        } else if (pkg.m_maxVersion != null && !pkg.m_maxVersion.isBlank()) {
            return "<" + pkg.m_maxVersion;
        } else {
            return "*";
        }
    }
}
