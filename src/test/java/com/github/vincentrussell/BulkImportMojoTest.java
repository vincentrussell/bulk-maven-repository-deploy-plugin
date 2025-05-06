package com.github.vincentrussell;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.alexpanov.net.FreePortFinder;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.testing.AbstractMojoTestCase;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.internal.impl.SimpleLocalRepositoryManagerFactory;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.eclipse.aether.repository.NoLocalRepositoryManagerException;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Random;

public class BulkImportMojoTest extends AbstractMojoTestCase {

    private final int httpPort = FreePortFinder.findFreeLocalPort();
    private final String artifactId = "cool-artifact";
    private final String subDirectory = "sub-directory";
    private final String releaseVersion = "1.0";
    private final String snapshotVersion = "1.0-SNAPSHOT";

    private Server jettyServer;
    private File jettyNexusBaseDir;
    private File localBaseDir;
    private ArtifactRepository localRepo;
    private File localReleaseArtifactDir;
    private File remoteReleaseArtifactDir;
    private File localSnapshotArtifactDir;
    private File remoteSnapshotArtifactDir;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Override
    protected void setUp() throws Exception {
        temporaryFolder.create();
        jettyNexusBaseDir = temporaryFolder.newFolder("jetty-remote");
        localBaseDir = temporaryFolder.newFolder("local-base-dir");
        localRepo = createLocalArtifactRepository(localBaseDir);

        jettyServer = new Server();
        ServerConnector httpConnector = new ServerConnector(jettyServer);
        httpConnector.setPort(httpPort);
        jettyServer.setConnectors(new Connector[]{httpConnector});

        ServletContextHandler servletHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        NexusServlet nexusServlet = new NexusServlet(jettyNexusBaseDir);
        ServletHolder holder = new ServletHolder(nexusServlet);
        servletHandler.addServlet(holder, "/repository/thirdparty/*");
        jettyServer.setHandler(servletHandler);
        jettyServer.start();

        localReleaseArtifactDir = getBaseDirectoryForArtifact(localBaseDir, artifactId, releaseVersion);
        remoteReleaseArtifactDir = getBaseDirectoryForArtifact(jettyNexusBaseDir, artifactId, releaseVersion);
        localSnapshotArtifactDir = getBaseDirectoryForArtifact(localBaseDir, artifactId, snapshotVersion);
        remoteSnapshotArtifactDir = getBaseDirectoryForArtifact(jettyNexusBaseDir, artifactId, snapshotVersion);
        super.setUp();
    }

    @Override
    public void tearDown() throws Exception {
        jettyServer.stop();
        temporaryFolder.delete();
        super.tearDown();
    }

    @Test
    public void testDeployReleasesAndSnapshots() throws Exception {
        String config = """
                <repositoryId>thirdparty</repositoryId>
                <repositoryUrl>http://localhost:%d/repository/thirdparty/</repositoryUrl>
                <deploymentType>SNAPSHOT_AND_RELEASE</deploymentType>
                """.formatted(httpPort);

        MavenProject mavenProject = readMavenProject(new TestProjectConfig(temporaryFolder).getFile(config).getParentFile());
        MavenSession session = finishSessionCreation(newMavenSession(mavenProject));
        simulateLocalMavenFiles(localBaseDir, artifactId, releaseVersion);
        simulateLocalMavenFiles(localBaseDir, artifactId, snapshotVersion);

        MojoExecution execution = newMojoExecution("bulk-import");
        BulkImportMojo bulkImportMojo = (BulkImportMojo) lookupConfiguredMojo(session, execution);
        assertNotNull(localReleaseArtifactDir.listFiles());
        assertNotNull(localSnapshotArtifactDir.listFiles());
        assertNull(remoteReleaseArtifactDir.listFiles());
        assertNull(remoteSnapshotArtifactDir.listFiles());
        assertFalse(new File(remoteSnapshotArtifactDir, "maven-metadata.xml").exists());

        bulkImportMojo.execute();

        verifyDirsAreEqual(localReleaseArtifactDir.toPath(), remoteReleaseArtifactDir.toPath());
        //cant verify that directories are equal with snapshot because a timestamp is added to the filename
        assertTrue(new File(remoteSnapshotArtifactDir, "maven-metadata.xml").exists());
    }

    @Test
    public void testDeployReleasesOnly() throws Exception {
        String config = """
                <repositoryId>thirdparty</repositoryId>
                <repositoryUrl>http://localhost:%d/repository/thirdparty/</repositoryUrl>
                <deploymentType>RELEASE_ONLY</deploymentType>
                """.formatted(httpPort);

        MavenProject mavenProject = readMavenProject(new TestProjectConfig(temporaryFolder).getFile(config).getParentFile());
        MavenSession session = finishSessionCreation(newMavenSession(mavenProject));
        simulateLocalMavenFiles(localBaseDir, artifactId, releaseVersion);
        simulateLocalMavenFiles(localBaseDir, artifactId, snapshotVersion);

        MojoExecution execution = newMojoExecution("bulk-import");
        BulkImportMojo bulkImportMojo = (BulkImportMojo) lookupConfiguredMojo(session, execution);
        assertNotNull(localReleaseArtifactDir.listFiles());
        assertNotNull(localSnapshotArtifactDir.listFiles());
        assertNull(remoteReleaseArtifactDir.listFiles());
        assertNull(remoteSnapshotArtifactDir.listFiles());
        assertFalse(new File(remoteSnapshotArtifactDir, "maven-metadata.xml").exists());

        bulkImportMojo.execute();

        verifyDirsAreEqual(localReleaseArtifactDir.toPath(), remoteReleaseArtifactDir.toPath());
        //no snapshots uploaded
        assertFalse(new File(remoteSnapshotArtifactDir, "maven-metadata.xml").exists());
        assertNull(remoteSnapshotArtifactDir.listFiles());
    }


    @Test
    public void testDeploySnapshotsOnly() throws Exception {
        String config = """
                <repositoryId>thirdparty</repositoryId>
                <repositoryUrl>http://localhost:%d/repository/thirdparty/</repositoryUrl>
                <deploymentType>SNAPSHOT_ONLY</deploymentType>
                """.formatted(httpPort);

        MavenProject mavenProject = readMavenProject(new TestProjectConfig(temporaryFolder).getFile(config).getParentFile());
        MavenSession session = finishSessionCreation(newMavenSession(mavenProject));
        simulateLocalMavenFiles(localBaseDir, artifactId, releaseVersion);
        simulateLocalMavenFiles(localBaseDir, artifactId, snapshotVersion);

        MojoExecution execution = newMojoExecution("bulk-import");
        BulkImportMojo bulkImportMojo = (BulkImportMojo) lookupConfiguredMojo(session, execution);
        assertNotNull(localReleaseArtifactDir.listFiles());
        assertNotNull(localSnapshotArtifactDir.listFiles());
        assertNull(remoteReleaseArtifactDir.listFiles());
        assertNull(remoteSnapshotArtifactDir.listFiles());
        assertFalse(new File(remoteSnapshotArtifactDir, "maven-metadata.xml").exists());

        bulkImportMojo.execute();

        assertNull(remoteReleaseArtifactDir.listFiles());
        //cant verify that directories are equal with snapshot because a timestamp is added to the filename
        assertTrue(new File(remoteSnapshotArtifactDir, "maven-metadata.xml").exists());
    }

    @Test
    public void testDeployWithSubDirectory() throws Exception {
        String config = """
                <repositoryId>thirdparty</repositoryId>
                <repositoryUrl>http://localhost:%d/repository/thirdparty/</repositoryUrl>
                <deploymentType>SNAPSHOT_AND_RELEASE</deploymentType>
                <repositorySubDirectory>sub-directory/com/github/vincentrussell</repositorySubDirectory>
                """.formatted(httpPort);

        File subLocalReleaseArtifactDir = getBaseDirectoryForArtifact(localBaseDir, subDirectory, artifactId, releaseVersion);
        File subRemoteReleaseArtifactDir = getBaseDirectoryForArtifact(jettyNexusBaseDir, subDirectory, artifactId, releaseVersion);
        File subRemoteSnapshotArtifactDir = getBaseDirectoryForArtifact(jettyNexusBaseDir, subDirectory, artifactId, snapshotVersion);

        MavenProject mavenProject = readMavenProject(new TestProjectConfig(temporaryFolder).getFile(config).getParentFile());
        MavenSession session = finishSessionCreation(newMavenSession(mavenProject));
        simulateLocalMavenFiles(localBaseDir, artifactId, releaseVersion);
        simulateLocalMavenFiles(localBaseDir, artifactId, snapshotVersion);
        simulateLocalMavenFiles(localBaseDir, subDirectory, artifactId, releaseVersion);
        simulateLocalMavenFiles(localBaseDir, subDirectory, artifactId, snapshotVersion);

        MojoExecution execution = newMojoExecution("bulk-import");
        BulkImportMojo bulkImportMojo = (BulkImportMojo) lookupConfiguredMojo(session, execution);
        assertNotNull(localReleaseArtifactDir.listFiles());
        assertNotNull(localSnapshotArtifactDir.listFiles());
        assertNull(remoteReleaseArtifactDir.listFiles());
        assertNull(remoteSnapshotArtifactDir.listFiles());
        assertFalse(new File(remoteSnapshotArtifactDir, "maven-metadata.xml").exists());

        bulkImportMojo.execute();

        verifyDirsAreEqual(subLocalReleaseArtifactDir.toPath(), subRemoteReleaseArtifactDir.toPath());
        //cant verify that directories are equal with snapshot because a timestamp is added to the filename
        assertTrue(new File(subRemoteSnapshotArtifactDir, "maven-metadata.xml").exists());
        assertNull(remoteReleaseArtifactDir.listFiles());
        assertNull(remoteSnapshotArtifactDir.listFiles());
    }

    private static void verifyDirsAreEqual(final Path one, final Path other) throws IOException {
        Files.walkFileTree(one, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                FileVisitResult result = super.visitFile(path, attrs);

                // get the relative file name from path "one"
                Path relativize = one.relativize(path);
                // construct the path for the counterpart file in "other"
                Path pathInOther = other.resolve(relativize);

                File file = path.toFile();
                File fileInOther = pathInOther.toFile();
                assertTrue(file.exists());
                assertTrue(fileInOther.exists());
                assertEquals(file.getName(), fileInOther.getName());
                return result;
            }
        });
    }

    private MavenSession finishSessionCreation(MavenSession newMavenSession) throws NoLocalRepositoryManagerException {
        DefaultRepositorySystemSession defaultRepositorySystem = (DefaultRepositorySystemSession) newMavenSession.getRepositorySession();
        SimpleLocalRepositoryManagerFactory simpleLocalRepositoryManagerFactory = new SimpleLocalRepositoryManagerFactory();
        LocalRepositoryManager localRepositoryManager = simpleLocalRepositoryManagerFactory.newInstance(
                defaultRepositorySystem, new LocalRepository(localBaseDir));
        defaultRepositorySystem.setLocalRepositoryManager(localRepositoryManager);
        newMavenSession.getRequest().setLocalRepository(localRepo);
        return newMavenSession;
    }

    private void simulateLocalMavenFiles(File localBaseDir, String artifactId, String version) throws IOException {
        simulateLocalMavenFiles(localBaseDir, null, artifactId, version);
    }

    private void simulateLocalMavenFiles(File localBaseDir, String subDirectory, String artifactId, String version) throws IOException {
        File coolArtifactDir = getBaseDirectoryForArtifact(localBaseDir, subDirectory, artifactId, version);
        coolArtifactDir.mkdirs();
        createFile(artifactId, version, coolArtifactDir, ".jar");
        createFile(artifactId, version, coolArtifactDir, ".jar.sha1");
        createFile(artifactId, version, coolArtifactDir, "-javadoc.jar");
        createFile(artifactId, version, coolArtifactDir, "-javadoc.jar.sha1");
        createFile(artifactId, version, coolArtifactDir, ".pom");
        createFile(artifactId, version, coolArtifactDir, ".pom.sha1");
        createFile(artifactId, version, coolArtifactDir, "-sources.jar");
        createFile(artifactId, version, coolArtifactDir, "-sources.jar.sha1");
    }

    private File getBaseDirectoryForArtifact(File localBaseDir, String artifactId, String version) {
        return getBaseDirectoryForArtifact(localBaseDir, null, artifactId, version);
    }

    private File getBaseDirectoryForArtifact(File localBaseDir, String subDirectory, String artifactId, String version) {
        if (subDirectory == null) {
            return Paths.get(localBaseDir.getAbsolutePath(), "com", "github", "vincentrussell",
                    artifactId, version).toFile();
        } else {
            return Paths.get(localBaseDir.getAbsolutePath(), subDirectory, "com", "github", "vincentrussell",
                    artifactId, version).toFile();
        }
    }

    private void createFile(String artifactId, String version, File coolArtifactDir, String extension) throws IOException {
        File jarFile = new File(coolArtifactDir, artifactId + "-" + version + extension);
        FileUtils.writeByteArrayToFile(jarFile, getRandomByteArray());
    }

    private byte[] getRandomByteArray() {
        byte[] b = new byte[2000];
        new Random().nextBytes(b);
        return b;
    }

    private ArtifactRepository createLocalArtifactRepository(File localRepoDir) {
        return new MavenArtifactRepository("local",
                localRepoDir.toURI().toString(),
                new DefaultRepositoryLayout(),
                new ArtifactRepositoryPolicy(true, ArtifactRepositoryPolicy.UPDATE_POLICY_ALWAYS, ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE),
                new ArtifactRepositoryPolicy(true, ArtifactRepositoryPolicy.UPDATE_POLICY_ALWAYS, ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE)
        );
    }

    protected MavenProject readMavenProject(File basedir) throws Exception {
        File pom = new File(basedir, "pom.xml");
        MavenExecutionRequest request = new DefaultMavenExecutionRequest();
        request.setBaseDirectory(basedir);
        ProjectBuildingRequest configuration = request.getProjectBuildingRequest();
        DefaultRepositorySystemSession repositorySession = new DefaultRepositorySystemSession();
        configuration.setRepositorySession(repositorySession);
        MavenProject project = lookup(ProjectBuilder.class).build(pom, configuration).getProject();
        assertNotNull(project);
        return project;
    }

    public static class NexusServlet extends HttpServlet {
        private final File baseDir;

        public NexusServlet(File baseDir) {
            this.baseDir = baseDir;
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            String url = request.getRequestURI();
            if (url.endsWith("maven-metadata.xml")) {
                response.setContentType("application/xml");
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().println("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <metadata>
                          <groupId>com.github.vincentrussell</groupId>
                          <artifactId>doesnt-matter</artifactId>
                          <versioning>
                            <release>0.1.1</release>
                            <versions>
                              <version>0.1.1</version>
                            </versions>
                            <lastUpdated>20200608005752</lastUpdated>
                          </versioning>
                        </metadata>
                        """);
            }
        }

        @Override
        protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
            File file = Paths.get(baseDir.getAbsolutePath(), request.getPathInfo()).toFile();
            file.getParentFile().mkdirs();

            try (FileOutputStream fileOutputStream = new FileOutputStream(file)) {
                IOUtils.copy(request.getInputStream(), fileOutputStream);
            }

            response.setContentType("plain/text");
            response.setStatus(HttpServletResponse.SC_CREATED);
            response.getWriter().println("OK");
        }
    }
}