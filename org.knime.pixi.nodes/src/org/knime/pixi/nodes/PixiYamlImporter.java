package org.knime.pixi.nodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.knime.conda.envinstall.pixi.PixiBinary;
import org.knime.conda.envinstall.pixi.PixiBinary.CallResult;
import org.knime.conda.envinstall.pixi.PixiBinary.PixiBinaryLocationException;
import org.knime.core.node.KNIMEException;
import org.knime.pixi.port.PixiUtils;

/**
 * Utility class to import conda environment.yaml files into pixi format
 *
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 * @since 5.11
 */
public final class PixiYamlImporter {

    private PixiYamlImporter() {
        // Utility class
    }

    /**
     * Convert a conda environment.yaml to a pixi.toml manifest with all platforms enabled
     *
     * @param yamlContent the conda environment.yaml content
     * @return the pixi.toml content as string with all platforms configured
     */
    @SuppressWarnings("restriction")
    public static String convertYamlToToml(final String yamlContent) {
        Path tempDir = null;
        try {
            // Create temporary directory
            tempDir = Files.createTempDirectory("pixi-yaml-import-");
            Path envFile = tempDir.resolve("environment.yml");

            // Write yaml content to file
            Files.writeString(envFile, yamlContent);

            final String[] pixiArgs = {"init", "--import", envFile.toString(), "-p", "win-64", "-p", "linux-64", "-p",
                "osx-64", "-p", "osx-arm64"};
            final CallResult callResult = PixiBinary.callPixi(tempDir, Map.of(), pixiArgs);

            if (callResult.returnCode() != 0) {
                String errorDetails = PixiUtils.getMessageFromCallResult(callResult);
                throw new KNIMEException("Failed to import conda environment.yaml: " + errorDetails).toUnchecked();
            }

            // Read the generated pixi.toml
            Path tomlFile = tempDir.resolve("pixi.toml");
            if (!Files.exists(tomlFile)) {
                throw new KNIMEException("pixi init did not create pixi.toml file").toUnchecked();
            }

            String tomlContent = Files.readString(tomlFile);

            // Parse the TOML, override platforms, and write back
            return tomlContent;

        } catch (IOException e) {
            throw new KNIMEException("Could not import conda environment.yaml", e).toUnchecked();
        } catch (PixiBinaryLocationException e) {
            throw new KNIMEException("Could not find pixi executable", e).toUnchecked();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KNIMEException("Pixi call got interrupted", e).toUnchecked();
        } finally {
            // Clean up temp directory
            if (tempDir != null) {
                try {
                    FileUtils.deleteDirectory(tempDir.toFile());
                } catch (IOException e) {
                    // Best effort cleanup
                }
            }
        }
    }

}
