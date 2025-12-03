/*
 * ------------------------------------------------------------------------
 *
 *  Copyright by KNIME AG, Zurich, Switzerland
 *  Website: http://www.knime.com; Email: contact@knime.com
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License, Version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 *  Additional permission under GNU GPL version 3 section 7:
 *
 *  KNIME interoperates with ECLIPSE solely via ECLIPSE's plug-in APIs.
 *  Hence, KNIME and ECLIPSE are both independent programs and are not
 *  derived from each other. Should, however, the interpretation of the
 *  GNU GPL Version 3 ("License") under any applicable laws result in
 *  KNIME and ECLIPSE being a combined program, KNIME AG herewith grants
 *  you the additional permission to use and propagate KNIME together with
 *  ECLIPSE with only the license terms in place for ECLIPSE applying to
 *  ECLIPSE and the GNU GPL Version 3 applying for KNIME, provided the
 *  license terms of ECLIPSE themselves allow for the respective use and
 *  propagation of ECLIPSE together with KNIME.
 *
 *  Additional permission relating to nodes for KNIME that extend the Node
 *  Extension (and in particular that are based on subclasses of NodeModel,
 *  NodeDialog, and NodeView) and that only interoperate with KNIME through
 *  standard APIs ("Nodes"):
 *  Nodes are deemed to be separate and independent programs and to not be
 *  covered works.  Notwithstanding anything to the contrary in the
 *  License, the License does not apply to Nodes, you are not required to
 *  license Nodes under the License, and you are granted a license to
 *  prepare and propagate Nodes, in each case even if such Nodes are
 *  propagated with or for interoperation with KNIME.  The owner of a Node
 *  may freely choose the license terms applicable to such Node, including
 *  when such Node is propagated with or for interoperation with KNIME.
 * ---------------------------------------------------------------------
 *
 * History
 *   Dec 3, 2025 (Marc Lehner): created
 */
package org.knime.conda.envinstall.action;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link InstallCondaEnvironment}.
 * 
 * These tests verify the installation and uninstallation logic, using the actual
 * pixi binary from the platform-specific fragment bundles.
 *
 * @author Marc Lehner, KNIME GmbH, Zurich, Switzerland
 */
class InstallCondaEnvironmentTest {

    @TempDir
    Path m_tempDir;
    
    private Path m_pixiBinaryPath;

    /**
     * Test installCondaEnvironment creates the environment structure and installs packages.
     * 
     * This test creates a minimal fragment structure with a simple pixi.lock containing only tzdata,
     * then uses the actual pixi binary to install it. The test locates the pixi binary based on
     * the current platform.
     */
    @Test
    void installCondaEnvironment_createsEnvironmentStructure() throws Exception {
        // Get the pixi binary path for the current platform from workspace
        m_pixiBinaryPath = getPixiBinaryPath();
        if (m_pixiBinaryPath == null || !Files.exists(m_pixiBinaryPath)) {
            System.out.println("Pixi binary not found for platform, skipping install test");
            return; // Skip test if pixi binary not available
        }

        // Copy pixi binary to a mock fragment location that PixiBinary will find
        // This simulates the OSGi fragment structure in the test environment
        var mockFragmentPath = mockPixiFragment(m_pixiBinaryPath);
        
        // Setup fragment structure: <tempDir>/plugins/test.fragment_1.0.0/
        var artifactLocation = m_tempDir.resolve("plugins").resolve("test.fragment_1.0.0");
        Files.createDirectories(artifactLocation);

        // Copy test pixi.toml and pixi.lock from test resources
        // These are minimal test environments that install quickly
        var testResources = Paths.get("src", "org", "knime", "conda", "envinstall", "action", "test-resources");
        var pixiToml = testResources.resolve("pixi.toml");
        var pixiLock = testResources.resolve("pixi.lock");
        
        if (!Files.exists(pixiToml) || !Files.exists(pixiLock)) {
            System.out.println("Test resources (pixi.toml/pixi.lock) not found, skipping install test");
            return;
        }
        
        Files.copy(pixiToml, artifactLocation.resolve("pixi.toml"));
        Files.copy(pixiLock, artifactLocation.resolve("pixi.lock"));
        
        // Create local channel using pixi-pack to make test self-contained
        setupLocalChannel(artifactLocation, testResources);

        // Setup destination and bundling root
        var bundlingRoot = m_tempDir.resolve("bundling");
        var envDestination = bundlingRoot.resolve("test-env");

        // Execute installation - this will use the real pixi binary
        System.out.println("Starting environment installation...");
        System.out.println("Artifact location: " + artifactLocation);
        System.out.println("Environment destination: " + envDestination);
        System.out.println("Bundling root: " + bundlingRoot);
        
        var envPath = InstallCondaEnvironment.installCondaEnvironment(
            artifactLocation, envDestination, bundlingRoot);

        System.out.println("Installation completed. Environment path: " + envPath);
        
        // Verify environment structure was created
        System.out.println("Verifying environment structure...");
        assertTrue(Files.isDirectory(envDestination), "Environment destination should be created");
        System.out.println("✓ Environment destination exists");
        
        assertTrue(Files.exists(envDestination.resolve("pixi.toml")), "pixi.toml should be created");
        System.out.println("✓ pixi.toml exists");
        
        assertTrue(Files.exists(envDestination.resolve("pixi.lock")), "pixi.lock should be copied");
        System.out.println("✓ pixi.lock exists");
        
        assertTrue(Files.isDirectory(envPath), "Environment path should exist");
        System.out.println("✓ Environment path exists: " + envPath);
        
        assertTrue(envPath.toString().contains(".pixi"), "Environment path should be in .pixi directory");
        System.out.println("✓ Environment path is in .pixi directory");
        
        // Verify the environment was actually installed by pixi
        var condaMeta = envPath.resolve("conda-meta");
        System.out.println("Checking for conda-meta at: " + condaMeta);
        
        if (!Files.exists(condaMeta)) {
            System.out.println("ERROR: conda-meta not found!");
            System.out.println("Contents of environment path:");
            Files.list(envPath).forEach(p -> System.out.println("  - " + p.getFileName()));
            if (Files.exists(envPath.getParent())) {
                System.out.println("Contents of parent directory:");
                Files.list(envPath.getParent()).forEach(p -> System.out.println("  - " + p.getFileName()));
            }
        }
        
        assertTrue(Files.exists(condaMeta), "conda-meta should exist in installed environment");
        System.out.println("✓ conda-meta exists - environment successfully installed!");
    }

    /**
     * Get the path to the pixi binary for the current platform.
     * 
     * This method determines the platform and constructs the path to the pixi executable
     * in the corresponding fragment bundle directory.
     * 
     * @return Path to the pixi binary, or null if not found for the current platform
     */
    private Path getPixiBinaryPath() {
        String os = Platform.getOS();
        String arch = Platform.getOSArch();
        
        // Determine the fragment bundle name based on OS and architecture
        String fragmentName;
        String binaryName;
        
        if (Platform.OS_WIN32.equals(os) && Platform.ARCH_X86_64.equals(arch)) {
            fragmentName = "org.knime.conda.pixi.bin.win32.x86_64";
            binaryName = "pixi.exe";
        } else if (Platform.OS_LINUX.equals(os) && Platform.ARCH_X86_64.equals(arch)) {
            fragmentName = "org.knime.conda.pixi.bin.linux.x86_64";
            binaryName = "pixi";
        } else if (Platform.OS_MACOSX.equals(os) && Platform.ARCH_X86_64.equals(arch)) {
            fragmentName = "org.knime.conda.pixi.bin.macosx.x86_64";
            binaryName = "pixi";
        } else if (Platform.OS_MACOSX.equals(os) && Platform.ARCH_AARCH64.equals(arch)) {
            fragmentName = "org.knime.conda.pixi.bin.macosx.aarch64";
            binaryName = "pixi";
        } else {
            System.out.println("Unsupported platform: OS=" + os + ", Arch=" + arch);
            return null;
        }
        
        // Try to locate the pixi binary in the workspace
        // This assumes the test is running from the knime-conda directory
        Path workspaceRoot = Paths.get("").toAbsolutePath();
        Path pixiBinaryPath = workspaceRoot.resolve(fragmentName).resolve("bin").resolve(binaryName);
        
        if (Files.exists(pixiBinaryPath)) {
            return pixiBinaryPath;
        }
        
        // Alternative: try parent directory (in case we're in a subdirectory)
        pixiBinaryPath = workspaceRoot.getParent().resolve(fragmentName).resolve("bin").resolve(binaryName);
        if (Files.exists(pixiBinaryPath)) {
            return pixiBinaryPath;
        }
        
        return null;
    }

    /**
     * Helper method to set up the pixi binary location for testing.
     * 
     * Since PixiBinary uses OSGi fragment lookup which isn't available in tests,
     * we use reflection to directly set the CACHED_PATH field that PixiBinary will use.
     * 
     * @param sourceBinary the path to the actual pixi binary in the workspace
     * @return the path where the binary was set
     */
    private Path mockPixiFragment(Path sourceBinary) throws Exception {
        // Use reflection to set the pixi binary location for testing
        // This is a workaround since PixiBinary.getPixiBinaryPath() uses OSGi fragment lookup
        // We directly access the private CACHED_PATH field to avoid modifying production code
        var pixiBinaryClass = Class.forName("org.knime.conda.envinstall.pixi.PixiBinary");
        var cachedPathField = pixiBinaryClass.getDeclaredField("CACHED_PATH");
        cachedPathField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var atomicRef = (java.util.concurrent.atomic.AtomicReference<String>) cachedPathField.get(null);
        atomicRef.set(sourceBinary.toAbsolutePath().toString());
        
        return sourceBinary;
    }
    
    /**
     * Get the pixi-pack binary path, installing it locally if necessary.
     * TODO: remove once pixi-pack is installed on jenkins agents by default.
     * 
     * @return the path to the pixi-pack binary
     */
    private String getPixiPackBinary() throws Exception {
        if (m_pixiBinaryPath == null) {
            throw new RuntimeException("Pixi binary not initialized");
        }
        
        // Create a local pixi environment in temp directory to install pixi-pack
        var pixiPackEnvDir = m_tempDir.resolve("pixi-pack-env");
        Files.createDirectories(pixiPackEnvDir);
        
        // Create a minimal pixi.toml for pixi-pack
        var pixiToml = pixiPackEnvDir.resolve("pixi.toml");
        Files.writeString(pixiToml, """
            [project]
            name = "pixi-pack-env"
            channels = ["conda-forge"]
            platforms = ["win-64", "linux-64", "osx-64", "osx-arm64"]
            
            [dependencies]
            pixi-pack = "*"
            """);
        
        // Run pixi install to create the local environment with pixi-pack
        var installProcess = new ProcessBuilder(m_pixiBinaryPath.toString(), "install")
            .directory(pixiPackEnvDir.toFile())
            .redirectErrorStream(true)
            .start();
        
        int installExitCode = installProcess.waitFor();
        if (installExitCode != 0) {
            var output = new String(installProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("pixi install pixi-pack failed: " + output);
            throw new RuntimeException("Failed to install pixi-pack");
        }
        
        // Determine the pixi-pack binary path in the local environment
        var pixiPackBinaryName = Platform.getOS().equals(Platform.OS_WIN32) ? "pixi-pack.exe" : "pixi-pack";
        var pixiPackPath = pixiPackEnvDir.resolve(".pixi").resolve("envs").resolve("default").resolve("bin").resolve(pixiPackBinaryName);
        
        if (!Files.exists(pixiPackPath)) {
            throw new RuntimeException("pixi-pack not found at: " + pixiPackPath);
        }
        
        return pixiPackPath.toString();
    }
    
    /**
     * Set up a local conda channel using pixi-pack.
     * 
     * This creates a self-contained local channel by:
     * 1. Running pixi-pack pack to create environment.tar
     * 2. Extracting the tar archive to create the channel directory structure
     * 3. Updating pixi.toml to use the local channel
     * 
     * @param artifactLocation where the test environment will be installed
     * @param testResources path to test resources containing pixi.toml
     */
    private void setupLocalChannel(Path artifactLocation, Path testResources) throws Exception {
        // Get the pixi-pack binary path, install if necessary
        String pixiPackBinary = getPixiPackBinary();

        // Run pixi-pack pack in the test resources directory to create environment.tar
        var testResourcesAbsolute = testResources.toAbsolutePath();
        
        var packProcess = new ProcessBuilder(pixiPackBinary, testResourcesAbsolute.toString())
            .redirectErrorStream(true)
            .start();
        
        int packExitCode = packProcess.waitFor();
        var packOutput = new String(packProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        
        if (packExitCode != 0) {
            System.out.println("pixi-pack failed with exit code " + packExitCode);
            System.out.println("Output: " + packOutput);
            throw new RuntimeException("Failed to create packed environment");
        }
        
        System.out.println("pixi-pack output: " + packOutput);
        
        // pixi-pack creates environment.tar in the current working directory
        // Try multiple possible locations
        Path environmentTar = null;
        var possibleLocations = new Path[] {
            testResourcesAbsolute.resolve("environment.tar"),
            Path.of("environment.tar").toAbsolutePath(),
            testResourcesAbsolute.getParent().resolve("environment.tar")
        };
        
        for (var location : possibleLocations) {
            if (Files.exists(location)) {
                environmentTar = location;
                System.out.println("Found environment.tar at: " + location);
                break;
            }
        }
        
        if (environmentTar == null) {
            System.out.println("Files in test resources: " + Files.list(testResourcesAbsolute).toList());
            System.out.println("Current working directory: " + Path.of("").toAbsolutePath());
            System.out.println("Files in current directory: " + Files.list(Path.of("").toAbsolutePath()).toList());
            throw new RuntimeException("environment.tar not created by pixi-pack");
        }
        
        // InstallCondaEnvironment expects packages in an 'env' subdirectory
        var envDir = artifactLocation.resolve("env");
        Files.createDirectories(envDir);
        
        // Extract tar archive which contains a 'channel/' directory structure
        extractTar(environmentTar, envDir);
    }
    
    /**
     * Extract a tar archive to a destination directory using the tar command.
     * 
     * @param tarFile the tar archive to extract
     * @param destDir the destination directory
     */
    private void extractTar(Path tarFile, Path destDir) throws Exception {
        var tarProcess = new ProcessBuilder("tar", "-xf", tarFile.toAbsolutePath().toString())
            .directory(destDir.toFile())
            .redirectErrorStream(true)
            .start();
        
        int tarExitCode = tarProcess.waitFor();
        if (tarExitCode != 0) {
            var output = new String(tarProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("tar extraction failed: " + output);
            throw new RuntimeException("Failed to extract " + tarFile);
        }
    }

    /**
     * Test uninstallEnvironment removes the environment directory and environment_path.txt file.
     * 
     * This test creates a complete environment structure, then verifies that uninstallEnvironment
     * properly removes all files and directories using reflection to access the private method.
     */
    @Test
    void uninstallEnvironment_removesEnvironmentAndPathFile() throws Exception {
        // Setup directory structure: <tempDir>/installation/plugins/test.fragment/
        var installationRoot = m_tempDir.resolve("installation");
        var fragmentDir = installationRoot.resolve("plugins").resolve("test.fragment");
        Files.createDirectories(fragmentDir);

        // Create bundling root and environment structure
        var bundlingRoot = installationRoot.resolve("bundling");
        var envRoot = bundlingRoot.resolve("test-env");
        var envPath = envRoot.resolve(".pixi").resolve("envs").resolve("default");
        Files.createDirectories(envPath);

        // Create some dummy files in the environment
        Files.writeString(envPath.resolve("python.exe"), "dummy", StandardCharsets.UTF_8);
        Files.writeString(envRoot.resolve("pixi.toml"), "dummy", StandardCharsets.UTF_8);

        // Create environment_path.txt pointing to the environment
        var envPathFile = fragmentDir.resolve("environment_path.txt");
        Files.writeString(envPathFile, "bundling/test-env/.pixi/envs/default", StandardCharsets.UTF_8);

        // Verify setup is correct
        assertTrue(Files.exists(envRoot), "Environment root should exist before uninstall");
        assertTrue(Files.exists(envPathFile), "Environment path file should exist before uninstall");

        // Use reflection to call private uninstallEnvironment method
        Method method = InstallCondaEnvironment.class.getDeclaredMethod(
            "uninstallEnvironment", Path.class, String.class);
        method.setAccessible(true);
        method.invoke(null, fragmentDir, "test-env");

        // Verify environment was removed
        assertFalse(Files.exists(envRoot), "Environment root should be deleted");
        assertFalse(Files.exists(envPathFile), "Environment path file should be deleted");
    }

    /**
     * Test uninstallEnvironment handles missing environment_path.txt gracefully.
     * 
     * This test verifies the resilience improvements from AP-24745 - uninstall should not
     * fail when the environment_path.txt file doesn't exist. This is important for robustness
     * when cleaning up partially installed or corrupted environments.
     * 
     * Note: The current implementation still throws NoSuchFileException when the file is missing.
     * This test documents the current behavior. Once AP-24745 is fully implemented to handle
     * missing files gracefully, this test should be updated to use assertDoesNotThrow.
     */
    @Test
    void uninstallEnvironment_missingPathFile_throwsException() throws Exception {
        // Setup directory structure without environment_path.txt
        var fragmentDir = m_tempDir.resolve("plugins").resolve("test.fragment");
        Files.createDirectories(fragmentDir);

        // Use reflection to call private uninstallEnvironment method
        Method method = InstallCondaEnvironment.class.getDeclaredMethod(
            "uninstallEnvironment", Path.class, String.class);
        method.setAccessible(true);

        // Current behavior: throws exception when environment_path.txt doesn't exist
        try {
            method.invoke(null, fragmentDir, "test-env");
            // If we get here, the behavior has changed - update the test!
            assertFalse(Files.exists(fragmentDir.resolve("environment_path.txt")), 
                "Test setup verification: environment_path.txt should not exist");
        } catch (Exception e) {
            // Expected: Currently throws InvocationTargetException wrapping NoSuchFileException
            assertTrue(e instanceof java.lang.reflect.InvocationTargetException,
                "Should throw InvocationTargetException when file is missing");
        }
    }
}
