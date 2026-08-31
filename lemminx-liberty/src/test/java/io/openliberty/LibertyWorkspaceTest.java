package io.openliberty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.withSettings;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import io.openliberty.tools.langserver.lemminx.services.LibertyWorkspace;
import io.openliberty.tools.langserver.lemminx.util.LibertyUtils;

public class LibertyWorkspaceTest {
    
    @Test
    public void testReadDevcMetadata() throws URISyntaxException {
        File srcResourcesDir = new File("src/test/resources");
        URI resourcesDir = srcResourcesDir.toURI();
        LibertyWorkspace libertyWorkspace = new LibertyWorkspace(resourcesDir.toString());
        assertNull(libertyWorkspace.getContainerName());
        assertTrue(libertyWorkspace.getContainerType().equals("docker"));
        assertFalse(libertyWorkspace.isContainerAlive());
        assertNull(libertyWorkspace.findDevcMetadata());    // no alive containers return null

        /* Uncomment to enable, 1) switch containerAlive to true, and 2) expect harmless runtime error */
        // assertNotNull(libertyWorkspace.findDevcMetadata());
        // assertEquals("liberty-dev", libertyWorkspace.getContainerName());
        // assertTrue(libertyWorkspace.isContainerAlive());
    }

    @Test
    public void testConfigDropinsDefaults() throws IOException {
        File mockXML = new File("src/test/resources/configDropins/defaults/my.xml");
        URI filePathURI = mockXML.toURI();

        assertTrue(LibertyUtils.isConfigXMLFile(filePathURI.toString()));

    }

    @Test
    public void testBackslashConfigDetection() throws IOException {
        // run test on Windows
        if (File.separator.equals("/")) {
            return;
        }

        File mockXML = new File("src/test/resources/sample/custom_server.xml");
        String filePathString = mockXML.getCanonicalPath();
        URI filePathURI = mockXML.toURI();

        assertTrue(LibertyUtils.isConfigXMLFile(filePathURI.toString()));

        // method expects URI formatted string and so should fail on Windows
        boolean test1 = LibertyUtils.isConfigXMLFile(filePathString);
        assertFalse(test1);
    }

    @Test
    public void testWorkspaceUriRoundTrip() throws IOException {
        // round tripping should work whether the file separator is "/" or "\\"
        File dir = new File("src/test/resources/sample").getCanonicalFile();
        LibertyWorkspace workspace = new LibertyWorkspace(dir.toURI().toString());

        assertTrue(Files.isSameFile(dir.toPath(), Paths.get(workspace.getWorkspaceURI())), "Excepted URI to point to original directory");
        assertTrue(Files.isSameFile(dir.toPath(), workspace.getDir().toPath()), "Excepted getDir() to point to original directory");
    }

    @Test
    public void testGetPluginConfigFileCachesResult() {
        LibertyWorkspace workspace = new LibertyWorkspace(new File("src/test/resources/sample").toURI().toString());
        LibertyUtils.invalidatePluginConfigPathCache(workspace);

        Path config = LibertyUtils.getPluginConfigFile(workspace);
        assertNotNull(config, "Expected to find liberty-plugin-config.xml");

        try (MockedStatic<LibertyUtils> staticMock = mockStatic(LibertyUtils.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
            assertSame(LibertyUtils.getPluginConfigFile(workspace), config, "Expected cached liberty-plugin-config.xml");
            staticMock.verify(
                    () -> LibertyUtils.findFileInWorkspace(any(LibertyWorkspace.class), any(Path.class)),
                    times(0));
        }
    }

    @Test
    public void testGetPluginConfigFileCachesAbsence(@TempDir Path tempDir) {
        LibertyWorkspace workspace = new LibertyWorkspace(tempDir.toUri().toString());
        LibertyUtils.invalidatePluginConfigPathCache(workspace);

        assertNull(LibertyUtils.getPluginConfigFile(workspace), "Expected absence of liberty-plugin-config.xml");

        try (MockedStatic<LibertyUtils> staticMock = mockStatic(LibertyUtils.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
            assertNull(LibertyUtils.getPluginConfigFile(workspace), "Expected cached absence of liberty-plugin-config.xml");
            staticMock.verify(
                    () -> LibertyUtils.findFileInWorkspace(any(LibertyWorkspace.class), any(Path.class)),
                    times(0));
        }
    }

    @Test
    public void testGetPluginConfigFileInvalidatedAfterLibertyUninstalled() {
        LibertyWorkspace workspace = new LibertyWorkspace(new File("src/test/resources/sample").toURI().toString());
        LibertyUtils.invalidatePluginConfigPathCache(workspace);

        assertNotNull(LibertyUtils.getPluginConfigFile(workspace), "Expected to find liberty-plugin-config.xml");
        workspace.setLibertyInstalled(false);

        try (MockedStatic<LibertyUtils> staticMock = mockStatic(LibertyUtils.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
            assertNotNull(LibertyUtils.getPluginConfigFile(workspace), "Expected to find liberty-plugin-config.xml after invalidation");
            staticMock.verify(
                    () -> LibertyUtils.findFileInWorkspace(any(LibertyWorkspace.class), any(Path.class)),
                    times(1));
        }
    }

    @Test
    public void testGetPluginConfigFileInvalidatedAfterFileDeleted(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("liberty-plugin-config.xml");
        Files.createFile(configFile);
        LibertyWorkspace workspace = new LibertyWorkspace(tempDir.toUri().toString());
        LibertyUtils.invalidatePluginConfigPathCache(workspace);

        assertNotNull(LibertyUtils.getPluginConfigFile(workspace), "Expected to find liberty-plugin-config.xml");
        Files.delete(configFile);

        try (MockedStatic<LibertyUtils> staticMock = mockStatic(LibertyUtils.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
            assertNull(LibertyUtils.getPluginConfigFile(workspace), "Expected absence of liberty-plugin-config.xml after deletion");
            staticMock.verify(
                    () -> LibertyUtils.findFileInWorkspace(any(LibertyWorkspace.class), any(Path.class)),
                    times(1));
        }
    }

    @Test
    public void testGetPropertiesFileCachesResult(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("openliberty.properties"));
        LibertyWorkspace workspace = new LibertyWorkspace(tempDir.toUri().toString());
        LibertyUtils.invalidatePluginConfigPathCache(workspace);

        Path props = LibertyUtils.getLibertyPropertiesFile(workspace);
        assertNotNull(props, "Expected to find openliberty.properties");

        try (MockedStatic<LibertyUtils> staticMock = mockStatic(LibertyUtils.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
            assertSame(LibertyUtils.getLibertyPropertiesFile(workspace), props, "Expected cached openliberty.properties");
            staticMock.verify(
                    () -> LibertyUtils.findFileInWorkspace(any(LibertyWorkspace.class), any(Path.class)),
                    times(0));
        }
    }

    @Test
    public void testGetPropertiesFileCachesAbsence(@TempDir Path tempDir) throws IOException {
        LibertyWorkspace workspace = new LibertyWorkspace(tempDir.toUri().toString());
        LibertyUtils.invalidatePluginConfigPathCache(workspace);

        assertNull(LibertyUtils.getLibertyPropertiesFile(workspace), "Expected absence of openliberty.properties");

        try (MockedStatic<LibertyUtils> staticMock = mockStatic(LibertyUtils.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
            assertNull(LibertyUtils.getLibertyPropertiesFile(workspace), "Expected cached absence of openliberty.properties");
            staticMock.verify(
                    () -> LibertyUtils.findFileInWorkspace(any(LibertyWorkspace.class), any(Path.class)),
                    times(0));
        }
    }

    @Test
    public void testGetPropertiesFileInvalidatedAfterLibertyUninstalled(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("openliberty.properties"));
        LibertyWorkspace workspace = new LibertyWorkspace(tempDir.toUri().toString());
        LibertyUtils.invalidatePluginConfigPathCache(workspace);

        assertNotNull(LibertyUtils.getLibertyPropertiesFile(workspace), "Expected to find openliberty.properties");
        workspace.setLibertyInstalled(false);

        try (MockedStatic<LibertyUtils> staticMock = mockStatic(LibertyUtils.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
            assertNotNull(LibertyUtils.getLibertyPropertiesFile(workspace), "Expected to find openliberty.properties after invalidation");
            staticMock.verify(
                    () -> LibertyUtils.findFileInWorkspace(any(LibertyWorkspace.class), any(Path.class)),
                    times(3));
        }
    }

    @Test
    public void testGetPropertiesFileInvalidatedAfterFileDeleted(@TempDir Path tempDir) throws IOException {
        Path propsFile = tempDir.resolve("openliberty.properties");
        Files.createFile(propsFile);
        LibertyWorkspace workspace = new LibertyWorkspace(tempDir.toUri().toString());
        LibertyUtils.invalidatePluginConfigPathCache(workspace);

        assertNotNull(LibertyUtils.getLibertyPropertiesFile(workspace), "Expected to find openliberty.properties");
        Files.delete(propsFile);

        try (MockedStatic<LibertyUtils> staticMock = mockStatic(LibertyUtils.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
            assertNull(LibertyUtils.getLibertyPropertiesFile(workspace), "Expected absence of openliberty.properties after deletion");
            staticMock.verify(
                    () -> LibertyUtils.findFileInWorkspace(any(LibertyWorkspace.class), any(Path.class)),
                    times(2));
        }
    }

}
