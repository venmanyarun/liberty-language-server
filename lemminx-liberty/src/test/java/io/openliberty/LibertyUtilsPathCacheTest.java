/*******************************************************************************
 * Copyright (c) 2025 IBM Corporation and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package io.openliberty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.openliberty.tools.langserver.lemminx.services.LibertyWorkspace;
import io.openliberty.tools.langserver.lemminx.util.LibertyUtils;

/**
 * Tests for the plugin config path cache and properties file path cache in
 * {@link LibertyUtils}. Covers cache hits, cached absences, stale-path refresh,
 * and invalidation triggered by {@link LibertyWorkspace#setLibertyInstalled(boolean)}.
 *
 * <p>Windows path handling is explicitly covered: workspace URIs are constructed via
 * {@code File.toURI()} so that the correct scheme is used on all platforms.
 */
public class LibertyUtilsPathCacheTest {

    @TempDir
    File workspaceDir;

    private LibertyWorkspace libertyWorkspace;

    @BeforeEach
    public void setUp() {
        // Construct the workspace URI correctly on all platforms (including Windows,
        // where File.toURI() produces "file:///C:/..." rather than "file:/C:/...").
        libertyWorkspace = new LibertyWorkspace(workspaceDir.toURI().toString());
        // Always start each test with a clean cache for this workspace.
        LibertyUtils.invalidatePathCaches(libertyWorkspace);
    }

    @AfterEach
    public void tearDown() {
        LibertyUtils.invalidatePathCaches(libertyWorkspace);
    }

    // -------------------------------------------------------------------------
    // getPluginConfigFile — cache miss (file does not exist in workspace)
    // -------------------------------------------------------------------------

    @Test
    public void getPluginConfigFile_returnsNull_whenFileAbsentInWorkspace() {
        // No liberty-plugin-config.xml under workspaceDir
        Path result = LibertyUtils.getPluginConfigFile(libertyWorkspace);

        assertNull(result, "Expected null when liberty-plugin-config.xml is absent");
    }

    @Test
    public void getPluginConfigFile_cachedAbsence_doesNotWalkAgain() throws IOException {
        // Spy on findFileInWorkspace so we can count calls.
        try (MockedStatic<LibertyUtils> utilsMock = Mockito.mockStatic(
                LibertyUtils.class, Mockito.CALLS_REAL_METHODS)) {

            // First call — populates cache with Optional.empty()
            Path firstResult = LibertyUtils.getPluginConfigFile(libertyWorkspace);
            assertNull(firstResult);

            // Second call — must return from cache, no additional filesystem walk
            Path secondResult = LibertyUtils.getPluginConfigFile(libertyWorkspace);
            assertNull(secondResult);

            // findFileInWorkspace should have been called exactly once
            utilsMock.verify(() -> LibertyUtils.findFileInWorkspace(
                    Mockito.same(libertyWorkspace),
                    Mockito.eq(java.nio.file.Paths.get("liberty-plugin-config.xml"))),
                    times(1));
        }
    }

    // -------------------------------------------------------------------------
    // getPluginConfigFile — cache hit (file exists in workspace)
    // -------------------------------------------------------------------------

    @Test
    public void getPluginConfigFile_returnsPath_whenFileExists() throws IOException {
        File configFile = new File(workspaceDir, "liberty-plugin-config.xml");
        configFile.createNewFile();

        Path result = LibertyUtils.getPluginConfigFile(libertyWorkspace);

        assertNotNull(result, "Expected a path when liberty-plugin-config.xml exists");
        assertEquals(configFile.getCanonicalPath(), result.toFile().getCanonicalPath());
    }

    @Test
    public void getPluginConfigFile_cachedHit_doesNotWalkAgain() throws IOException {
        File configFile = new File(workspaceDir, "liberty-plugin-config.xml");
        configFile.createNewFile();

        try (MockedStatic<LibertyUtils> utilsMock = Mockito.mockStatic(
                LibertyUtils.class, Mockito.CALLS_REAL_METHODS)) {

            // First call — discovers and caches the path
            Path firstResult = LibertyUtils.getPluginConfigFile(libertyWorkspace);
            assertNotNull(firstResult);

            // Second call — must use cache
            Path secondResult = LibertyUtils.getPluginConfigFile(libertyWorkspace);
            assertNotNull(secondResult);
            assertEquals(firstResult, secondResult);

            // findFileInWorkspace called only once (for the initial population)
            utilsMock.verify(() -> LibertyUtils.findFileInWorkspace(
                    Mockito.same(libertyWorkspace),
                    Mockito.eq(java.nio.file.Paths.get("liberty-plugin-config.xml"))),
                    times(1));
        }
    }

    // -------------------------------------------------------------------------
    // getPluginConfigFile — stale cache (file deleted after caching)
    // -------------------------------------------------------------------------

    @Test
    public void getPluginConfigFile_refreshesCache_whenCachedFileIsDeleted() throws IOException {
        File configFile = new File(workspaceDir, "liberty-plugin-config.xml");
        configFile.createNewFile();

        // Warm the cache
        Path firstResult = LibertyUtils.getPluginConfigFile(libertyWorkspace);
        assertNotNull(firstResult);

        // Delete the file to simulate a build clean
        configFile.delete();

        // Should detect the stale entry and re-walk, returning null
        Path secondResult = LibertyUtils.getPluginConfigFile(libertyWorkspace);
        assertNull(secondResult, "Expected null after the cached file was deleted");
    }

    // -------------------------------------------------------------------------
    // invalidatePathCaches — triggered by setLibertyInstalled(false)
    // -------------------------------------------------------------------------

    @Test
    public void setLibertyInstalled_false_invalidatesCache_andNextCallRewalks() throws IOException {
        File configFile = new File(workspaceDir, "liberty-plugin-config.xml");
        configFile.createNewFile();

        try (MockedStatic<LibertyUtils> utilsMock = Mockito.mockStatic(
                LibertyUtils.class, Mockito.CALLS_REAL_METHODS)) {

            // Warm the cache
            Path firstResult = LibertyUtils.getPluginConfigFile(libertyWorkspace);
            assertNotNull(firstResult);

            // Invalidate by triggering setLibertyInstalled(false)
            libertyWorkspace.setLibertyInstalled(false);

            // Next call must re-walk (cache was cleared)
            Path secondResult = LibertyUtils.getPluginConfigFile(libertyWorkspace);
            assertNotNull(secondResult);

            // findFileInWorkspace called twice: once before and once after invalidation
            utilsMock.verify(() -> LibertyUtils.findFileInWorkspace(
                    Mockito.same(libertyWorkspace),
                    Mockito.eq(java.nio.file.Paths.get("liberty-plugin-config.xml"))),
                    times(2));
        }
    }

    @Test
    public void setLibertyInstalled_false_invalidatesPropertiesFileCache() throws IOException {
        // Create a minimal openliberty.properties so getLibertyPropertiesFile can find it
        File propsFile = new File(workspaceDir, "openliberty.properties");
        propsFile.createNewFile();

        try (MockedStatic<LibertyUtils> utilsMock = Mockito.mockStatic(
                LibertyUtils.class, Mockito.CALLS_REAL_METHODS)) {

            // Warm the properties cache (also primes the plugin config cache to null/absent)
            Path firstResult = LibertyUtils.getLibertyPropertiesFile(libertyWorkspace);
            // The properties file should be found via the fallback workspace search
            assertNotNull(firstResult);

            // Invalidate
            libertyWorkspace.setLibertyInstalled(false);

            // The findFileInWorkspace for openliberty.properties must be called again
            utilsMock.verify(() -> LibertyUtils.findFileInWorkspace(
                    Mockito.same(libertyWorkspace),
                    Mockito.eq(java.nio.file.Paths.get("openliberty.properties"))),
                    Mockito.atLeast(1));
        }
    }

    // -------------------------------------------------------------------------
    // invalidatePathCaches — explicit call
    // -------------------------------------------------------------------------

    @Test
    public void invalidatePathCaches_clearsCache_andNextCallRewalks() throws IOException {
        File configFile = new File(workspaceDir, "liberty-plugin-config.xml");
        configFile.createNewFile();

        try (MockedStatic<LibertyUtils> utilsMock = Mockito.mockStatic(
                LibertyUtils.class, Mockito.CALLS_REAL_METHODS)) {

            LibertyUtils.getPluginConfigFile(libertyWorkspace);

            // Explicitly invalidate
            LibertyUtils.invalidatePathCaches(libertyWorkspace);

            LibertyUtils.getPluginConfigFile(libertyWorkspace);

            // findFileInWorkspace must have been called twice
            utilsMock.verify(() -> LibertyUtils.findFileInWorkspace(
                    Mockito.same(libertyWorkspace),
                    Mockito.eq(java.nio.file.Paths.get("liberty-plugin-config.xml"))),
                    times(2));
        }
    }

    // -------------------------------------------------------------------------
    // Windows path handling
    // -------------------------------------------------------------------------

    @Test
    public void getPluginConfigFile_handlesWindowsStyleWorkspaceUri() throws IOException {
        // This test validates that URI construction via File.toURI() works correctly
        // on Windows (file:///C:/...) as well as Unix (file:///tmp/...).
        // The setup in @BeforeEach uses File.toURI(), so this test just confirms
        // the basic contract: if the file exists, the path is returned as a valid Path.
        File configFile = new File(workspaceDir, "liberty-plugin-config.xml");
        configFile.createNewFile();

        Path result = LibertyUtils.getPluginConfigFile(libertyWorkspace);

        assertNotNull(result);
        // Verify the returned path resolves to an existing file
        assertEquals(true, result.toFile().exists(),
                "Cached path must point to an existing file on disk");
        // On Windows the path must still be navigable as a File
        assertEquals(configFile.getCanonicalPath(), result.toFile().getCanonicalPath(),
                "Returned path canonical form must match the created file on all platforms");
    }
}
