package com.github.vincentrussell;

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
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.internal.impl.SimpleLocalRepositoryManagerFactory;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.eclipse.aether.repository.NoLocalRepositoryManagerException;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Random;

public class BulkImportMojoTest extends AbstractMojoTestCase {

    private int httpPort = FreePortFinder.findFreeLocalPort();
    private Server jettyServer;

    @Rule
    TemporaryFolder temporaryFolder = new TemporaryFolder();

    String artifactId = "cool-artifact";
    String releaseVersion = "1.0";
    String snapshotVersion = "1.0-SNAPSHOT";

    File jettyNexusBaseDir;
    File localBaseDir;
    ArtifactRepository localRepo;
    File localReleaseArtifactDir;
    File remoteReleaseArtifactDir;
    File localSnapshotArtifactDir;
    File remoteSnapshotArtifactDir;

    @Override
    protected void setUp() throws Exception {
        temporaryFolder.create();
        jettyNexusBaseDir = temporaryFolder.newFolder("jetty-remote");
        localBaseDir = temporaryFolder.newFolder("local-base-dir");
        localRepo = createLocalArtifactRepository(localBaseDir);
        jettyServer = new Server();
        ServerConnector httpConnector = new ServerConnector(jettyServer);
        ServletHandler servletHandler = new ServletHandler();
        NexusServlet nexusServlet = new NexusServlet(jettyNexusBaseDir);
        ServletHolder servletHolder = new ServletHolder(nexusServlet);
        servletHandler.addServletWithMapping(servletHolder, "/repository/thirdparty/*");
        httpConnector.setPort(httpPort);
        jettyServer.setConnectors(new Connector[] {httpConnector});
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
        String config = " <repositoryId>thirdparty</repositoryId>\n" +
                "                    <repositoryUrl>http://localhost:" + httpPort + "/repository/thirdparty/</repositoryUrl>\n" +
                "                    <deploymentType>SNAPSHOT_AND_RELEASE</deploymentType>";

        MavenProject mavenProject = readMavenProject(new TestProjectConfig(temporaryFolder).getFile(config).getParentFile());
        MavenSession session = finishSessionCreation(newMavenSession( mavenProject ));
        simulateLocalMavenFiles(localBaseDir, artifactId, releaseVersion);
        simulateLocalMavenFiles(localBaseDir, artifactId, snapshotVersion);

        MojoExecution execution = newMojoExecution( "bulk-import" );
        BulkImportMojo bulkImportMojo = (BulkImportMojo) lookupConfiguredMojo( session, execution );
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
        String config = " <repositoryId>thirdparty</repositoryId>\n" +
                "                    <repositoryUrl>http://localhost:" + httpPort + "/repository/thirdparty/</repositoryUrl>\n" +
                "                    <deploymentType>RELEASE_ONLY</deploymentType>";

        MavenProject mavenProject = readMavenProject(new TestProjectConfig(temporaryFolder).getFile(config).getParentFile());
        MavenSession session = finishSessionCreation(newMavenSession( mavenProject ));
        simulateLocalMavenFiles(localBaseDir, artifactId, releaseVersion);
        simulateLocalMavenFiles(localBaseDir, artifactId, snapshotVersion);

        MojoExecution execution = newMojoExecution( "bulk-import" );
        BulkImportMojo bulkImportMojo = (BulkImportMojo) lookupConfiguredMojo( session, execution );
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
        String config = " <repositoryId>thirdparty</repositoryId>\n" +
                "                    <repositoryUrl>http://localhost:" + httpPort + "/repository/thirdparty/</repositoryUrl>\n" +
                "                    <deploymentType>SNAPSHOT_ONLY</deploymentType>";

        MavenProject mavenProject = readMavenProject(new TestProjectConfig(temporaryFolder).getFile(config).getParentFile());
        MavenSession session = finishSessionCreation(newMavenSession( mavenProject ));
        simulateLocalMavenFiles(localBaseDir, artifactId, releaseVersion);
        simulateLocalMavenFiles(localBaseDir, artifactId, snapshotVersion);

        MojoExecution execution = newMojoExecution( "bulk-import" );
        BulkImportMojo bulkImportMojo = (BulkImportMojo) lookupConfiguredMojo( session, execution );
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

    private static void verifyDirsAreEqual(final Path one, final Path other) throws IOException {
        Files.walkFileTree(one, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file,
                                             BasicFileAttributes attrs) throws IOException {
                FileVisitResult result = super.visitFile(file, attrs);

                // get the relative file name from path "one"
                Path relativize = one.relativize(file);
                // construct the path for the counterpart file in "other"
                Path fileInOther = other.resolve(relativize);
                
                assertEquals(file.toFile().getName(), fileInOther.toFile().getName());
                return result;
            }
        });
    }

    private MavenSession finishSessionCreation(MavenSession newMavenSession) throws NoLocalRepositoryManagerException {
        DefaultRepositorySystemSession defaultRepositorySystem = (DefaultRepositorySystemSession) newMavenSession.getRepositorySession();
        SimpleLocalRepositoryManagerFactory simpleLocalRepositoryManagerFactory = new SimpleLocalRepositoryManagerFactory();
        LocalRepositoryManager localRepositoryManager = simpleLocalRepositoryManagerFactory.newInstance(defaultRepositorySystem, new LocalRepository(localBaseDir));
        defaultRepositorySystem.setLocalRepositoryManager(localRepositoryManager);
        newMavenSession.getRequest().setLocalRepository(localRepo);
        return newMavenSession;
    }

    private void simulateLocalMavenFiles(File localBaseDir, String artifactId, String version) throws IOException {
        File coolArtifactDir = getBaseDirectoryForArtifact(localBaseDir, artifactId, version);
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
        return Paths.get(localBaseDir.getAbsolutePath(), "com", "github", "vincentrussell", artifactId, version).toFile();
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

    protected MavenProject readMavenProject(File basedir )
            throws ProjectBuildingException, Exception
    {
        File pom = new File( basedir, "pom.xml" );
        MavenExecutionRequest request = new DefaultMavenExecutionRequest();
        request.setBaseDirectory( basedir );
        ProjectBuildingRequest configuration = request.getProjectBuildingRequest();
        DefaultRepositorySystemSession repositorySession = new DefaultRepositorySystemSession();
        configuration.setRepositorySession(repositorySession);
        MavenProject project = lookup( ProjectBuilder.class ).build( pom, configuration ).getProject();
        assertNotNull( project );
        return project;
    }



    public static class NexusServlet extends HttpServlet {

        private final File baseDir;

        public NexusServlet(File baseDir) {
            this.baseDir = baseDir;
        }

        protected void doGet(
                HttpServletRequest request,
                HttpServletResponse response)
                throws ServletException, IOException {

            String url = request.getRequestURI();

            if (url.endsWith("maven-metadata.xml")) {
                response.setContentType("application/xml");
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().println("" +
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<metadata>\n" +
                        "  <groupId>com.github.vincentrussell</groupId>\n" +
                        "  <artifactId>doesnt-matter</artifactId>\n" +
                        "  <versioning>\n" +
                        "    <release>0.1.1</release>\n" +
                        "    <versions>\n" +
                        "      <version>0.1.1</version>\n" +
                        "    </versions>\n" +
                        "    <lastUpdated>20200608005752</lastUpdated>\n" +
                        "  </versioning>\n" +
                        "</metadata>\n");
            }


        }

        protected void doPut(
                HttpServletRequest request,
                HttpServletResponse response)
                throws ServletException, IOException {

            String pathInfo = request.getPathInfo();

            File file = Paths.get(baseDir.getAbsolutePath(), request.getPathInfo()).toFile();
            file.getParentFile().mkdirs();

            try(FileOutputStream fileOutputStream = new FileOutputStream(file)) {
                IOUtils.copy(request.getInputStream(), fileOutputStream);
            }

            response.setContentType("plain/text");
            response.setStatus(HttpServletResponse.SC_CREATED);
            response.getWriter().println("OK");
        }
    }

}