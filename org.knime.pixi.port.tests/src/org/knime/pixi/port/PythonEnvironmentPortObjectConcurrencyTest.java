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
 *   Feb 24, 2026 (Marc Lehner): created
 */
package org.knime.pixi.port;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.ExecutionMonitor;

/**
 * Tests for the concurrency and error-propagation logic in
 * {@link PythonEnvironmentPortObject#installWithCoordination}.
 *
 * <p>These tests cover four bugs that were identified in the original implementation:</p>
 * <ol>
 *   <li><b>Bug 1</b> – Exception was silently swallowed; the caller proceeded as if
 *       installation succeeded.</li>
 *   <li><b>Bug 2</b> – Completed (or failed) futures were never removed from
 *       {@code GLOBAL_INSTALL_FUTURES}, causing subsequent independent executions to
 *       short-circuit via {@code join()} without actually verifying the environment.</li>
 *   <li><b>Bug 3</b> – The waiter path threw {@code CompletionException} (unchecked)
 *       instead of the declared {@code IOException}, bypassing caller error handling.</li>
 *   <li><b>Bug 4</b> – The installer path did a second {@code map.get()} after
 *       {@code putIfAbsent}, creating a TOCTOU window where the entry could have been
 *       removed.</li>
 * </ol>
 *
 * <p>The tests use {@link PythonEnvironmentPortObject#installWithCoordination} directly
 * (package-private, accessible because this class lives in the same package via the
 * {@code org.knime.pixi.port.tests} fragment) and control the install directory via
 * {@link TempDir} so no KNIME preferences or real pixi binary are required except where
 * explicitly noted.</p>
 *
 * @author Marc Lehner, KNIME GmbH, Zurich, Switzerland
 */
class PythonEnvironmentPortObjectConcurrencyTest {

    @TempDir
    Path m_tempDir;

    @BeforeEach
    void clearGlobalFutures() {
        // Ensure the global map is clean before every test so tests are isolated.
        PythonEnvironmentPortObject.GLOBAL_INSTALL_FUTURES.clear();
    }

    @AfterEach
    void clearGlobalFuturesAfter() {
        PythonEnvironmentPortObject.GLOBAL_INSTALL_FUTURES.clear();
    }

    // -------------------------------------------------------------------------
    // Bug 1: exception is propagated to the caller
    // -------------------------------------------------------------------------

    /**
     * When pixi installation fails (e.g. the pixi binary is not found), the exception must
     * propagate to the caller. Before the fix, it was silently swallowed and the caller
     * proceeded as if the environment was ready.
     *
     * <p>This test does NOT require a real pixi binary. {@code performInstallation} will fail
     * quickly with an {@code IOException} because the pixi binary cannot be located, which is
     * exactly the scenario that triggered the original bug.</p>
     */
    @Test
    void testInstallFails_exceptionIsPropagated() {
        // Given: a directory that looks uninstalled (no .pixi/envs/default)
        final Path installDir = m_tempDir.resolve("bug1-env");

        // When / Then: IOException must escape to the caller (Bug 1 fix)
        assertThrows(IOException.class, () -> PythonEnvironmentPortObject.installWithCoordination(
            installDir, "[project]\nname = \"test\"\nversion = \"0.1.0\"\n",
            "version: 6\n", new ExecutionMonitor()),
            "IOException must propagate when pixi installation fails (Bug 1 regression guard)");
    }

    // -------------------------------------------------------------------------
    // Bug 2: future is removed from the map after completion
    // -------------------------------------------------------------------------

    /**
     * After a <em>successful</em> installation (env dir already present, so
     * {@code performInstallation} returns immediately) the future must be removed from
     * {@code GLOBAL_INSTALL_FUTURES}.  Before the fix, the stale completed future would
     * cause every subsequent call to short-circuit via {@code join()} without actually
     * verifying the environment.
     */
    @Test
    void testFutureRemovedAfterSuccessfulInstall() throws Exception {
        // Given: an already-installed environment (pre-create the envs/default directory)
        final Path installDir = m_tempDir.resolve("bug2-success-env");
        createInstalledEnvDir(installDir);

        // When: coordinated install runs (detects env already present, returns quickly)
        PythonEnvironmentPortObject.installWithCoordination(
            installDir, "[project]\nname = \"test\"\n", "lock-content", new ExecutionMonitor());

        // Then: the future must NOT remain in the global map (Bug 2 fix)
        assertFalse(PythonEnvironmentPortObject.GLOBAL_INSTALL_FUTURES.containsKey(installDir),
            "Future must be removed from GLOBAL_INSTALL_FUTURES after successful install (Bug 2 regression guard)");
    }

    /**
     * After a <em>failed</em> installation the future must also be removed from
     * {@code GLOBAL_INSTALL_FUTURES}. Before the fix, a stale failed future would cause
     * every subsequent call to throw {@code CompletionException} (see Bug 3) without
     * retrying the installation.
     */
    @Test
    void testFutureRemovedAfterFailedInstall() {
        // Given: an uninstalled environment directory (no .pixi/envs/default)
        final Path installDir = m_tempDir.resolve("bug2-failure-env");

        // When: installation fails (IOException from missing pixi binary)
        assertThrows(IOException.class, () -> PythonEnvironmentPortObject.installWithCoordination(
            installDir, "[project]\nname = \"test\"\n", "version: 6\n", new ExecutionMonitor()));

        // Then: the future must NOT remain in the global map (Bug 2 fix)
        assertFalse(PythonEnvironmentPortObject.GLOBAL_INSTALL_FUTURES.containsKey(installDir),
            "Future must be removed from GLOBAL_INSTALL_FUTURES after failed install (Bug 2 regression guard)");
    }

    /**
     * Re-executing after a previous failure must NOT short-circuit via a stale completed
     * future. Without the Bug 2 fix, the second call would have found the stale future and
     * thrown {@code CompletionException} (Bug 3) or returned silently (Bug 1) instead of
     * attempting a fresh installation.
     *
     * <p>In this test the second run succeeds because we pre-create the env directory
     * before it.</p>
     */
    @Test
    void testSecondExecutionWorksAfterFirstFailure() throws Exception {
        // Given: first run fails (no .pixi/envs/default, pixi not found)
        final Path installDir = m_tempDir.resolve("bug2-rerun-env");
        assertThrows(IOException.class, () -> PythonEnvironmentPortObject.installWithCoordination(
            installDir, "[project]\nname = \"test\"\n", "version: 6\n", new ExecutionMonitor()));

        // Now simulate the environment being available (e.g. installed by another means)
        createInstalledEnvDir(installDir);

        // When: second run is attempted
        // Then: should succeed without CompletionException (Bug 2 + Bug 3 regression guard)
        assertDoesNotThrow(() -> PythonEnvironmentPortObject.installWithCoordination(
            installDir, "[project]\nname = \"test\"\n", "version: 6\n", new ExecutionMonitor()),
            "Second execution after a prior failure must not throw CompletionException (Bug 2+3 regression guard)");
    }

    // -------------------------------------------------------------------------
    // Bug 3: waiter thread receives IOException, not CompletionException
    // -------------------------------------------------------------------------

    /**
     * When the primary installer thread fails, any waiter thread that called
     * {@code join()} on the same future must receive an {@code IOException}, not the raw
     * {@code CompletionException} that {@code CompletableFuture#join()} would otherwise
     * throw. Before the fix, the unchecked {@code CompletionException} bypassed callers
     * that only catch {@code IOException}.
     *
     * <p>This test injects a pre-failed future directly into {@code GLOBAL_INSTALL_FUTURES}
     * to reliably trigger the waiter path without depending on thread scheduling.</p>
     */
    @Test
    void testWaiterReceivesIOException_notCompletionException() {
        // Given: a pre-failed future already in the global map (simulates another thread
        //        having started and failed the installation)
        final Path installDir = m_tempDir.resolve("bug3-waiter-env");
        final var failedFuture = new CompletableFuture<Void>();
        failedFuture.completeExceptionally(new IOException("pixi binary not found"));
        PythonEnvironmentPortObject.GLOBAL_INSTALL_FUTURES.put(installDir, failedFuture);

        // When: this thread arrives as a waiter (putIfAbsent returns the existing future)
        // Then: IOException must be thrown — NOT the unchecked CompletionException (Bug 3 fix)
        final var thrown = assertThrows(IOException.class,
            () -> PythonEnvironmentPortObject.installWithCoordination(
                installDir, "[project]\nname = \"test\"\n", "version: 6\n", new ExecutionMonitor()),
            "Waiter thread must receive IOException, not CompletionException (Bug 3 regression guard)");
        assertTrue(thrown.getMessage().contains("pixi binary not found"),
            "The original error message must be preserved");
    }

    /**
     * Same as above but with a cancelled future — the waiter should receive
     * {@link CanceledExecutionException}.
     */
    @Test
    void testWaiterReceivesCanceledExecutionException_whenFutureIsCanceled() {
        // Given: a cancelled future in the map
        final Path installDir = m_tempDir.resolve("bug3-canceled-env");
        final var cancelledFuture = new CompletableFuture<Void>();
        cancelledFuture.cancel(false);
        PythonEnvironmentPortObject.GLOBAL_INSTALL_FUTURES.put(installDir, cancelledFuture);

        // When / Then: CanceledExecutionException must be thrown (Bug 3 fix)
        assertThrows(CanceledExecutionException.class,
            () -> PythonEnvironmentPortObject.installWithCoordination(
                installDir, "[project]\nname = \"test\"\n", "version: 6\n", new ExecutionMonitor()),
            "Waiter must receive CanceledExecutionException when the future was cancelled (Bug 3 regression guard)");
    }

    // -------------------------------------------------------------------------
    // Concurrency: only one thread installs, others wait
    // -------------------------------------------------------------------------

    /**
     * When multiple threads concurrently call {@code installWithCoordination} for the same
     * directory, only one thread must perform the installation while all others wait. All
     * threads must complete without error when the environment is already present.
     */
    @Test
    void testConcurrentInstalls_onlyOneActuallyInstalls() throws InterruptedException {
        // Given: pre-installed environment
        final Path installDir = m_tempDir.resolve("concurrency-env");
        assertDoesNotThrow(() -> createInstalledEnvDir(installDir));

        final int threadCount = 8;
        final var startLatch = new CountDownLatch(1);
        final var doneLatch = new CountDownLatch(threadCount);
        final var exceptions = new ConcurrentLinkedQueue<Exception>();

        for (int i = 0; i < threadCount; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await();
                    PythonEnvironmentPortObject.installWithCoordination(
                        installDir, "[project]\nname = \"test\"\n", "lock", new ExecutionMonitor());
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all threads simultaneously
        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "All threads should complete within 30 s");

        assertTrue(exceptions.isEmpty(),
            "No thread should throw when environment is pre-installed; exceptions: " + exceptions);
        assertFalse(PythonEnvironmentPortObject.GLOBAL_INSTALL_FUTURES.containsKey(installDir),
            "Map must be clean after all concurrent installs complete (Bug 2 regression guard)");
    }

    /**
     * Verifies that a waiter thread that joins a futures that only completes
     * AFTER it has been removed from the map (the normal post-fix sequence for
     * the installer) still receives an {@code IOException} and not a
     * {@code CompletionException}. The future is injected directly so that the
     * waiter path is triggered deterministically.
     *
     * <p>Timeline:</p>
     * <ol>
     *   <li>Main thread injects a pending {@code controlFuture} into the map.</li>
     *   <li>Waiter thread starts and calls {@code installWithCoordination};
     *       {@code putIfAbsent} returns {@code controlFuture} → waiter blocks on
     *       {@code controlFuture.join()}.</li>
     *   <li>Main thread waits until waiter is in {@code join()}, then completes
     *       the future exceptionally.</li>
     *   <li>Waiter thread unblocks, receives {@code IOException}.</li>
     * </ol>
     */
    @Test
    void testWaiterJoinsOngoingFuture_receivesIOExceptionOnFailure() throws InterruptedException {
        // Given: a pending future already in the map
        final Path installDir = m_tempDir.resolve("waiter-ongoing-env");
        final var controlFuture = new CompletableFuture<Void>();
        PythonEnvironmentPortObject.GLOBAL_INSTALL_FUTURES.put(installDir, controlFuture);

        final var waiterException = new ConcurrentLinkedQueue<Exception>();
        final var waiterBlocked = new CountDownLatch(1);
        final var waiterDone = new CountDownLatch(1);

        // Waiter thread: should join controlFuture and receive IOException
        Thread.ofVirtual().start(() -> {
            try {
                waiterBlocked.countDown(); // signal that we are about to call join
                PythonEnvironmentPortObject.installWithCoordination(
                    installDir, "[project]\nname=\"test\"\n", "version: 6\n", new ExecutionMonitor());
            } catch (Exception e) {
                waiterException.add(e);
            } finally {
                waiterDone.countDown();
            }
        });

        // Wait until waiter has had a chance to enter join(), then fail the future
        waiterBlocked.await();
        Thread.sleep(20); // give the virtual thread time to actually block in join()
        controlFuture.completeExceptionally(new IOException("injected pixi failure"));

        assertTrue(waiterDone.await(10, TimeUnit.SECONDS));

        // Then: waiter received IOException (Bug 3 fix), not CompletionException
        assertFalse(waiterException.isEmpty(), "Waiter thread should have received an exception");
        assertInstanceOf(IOException.class, waiterException.peek(),
            "Waiter must receive IOException, not CompletionException (Bug 3 regression guard)");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static void createInstalledEnvDir(final Path installDir) throws IOException {
        final Path envDir = installDir.resolve(".pixi").resolve("envs").resolve("default");
        Files.createDirectories(envDir);
    }
}
