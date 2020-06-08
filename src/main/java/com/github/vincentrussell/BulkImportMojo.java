package com.github.vincentrussell;


import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.shared.transfer.artifact.deploy.ArtifactDeployer;
import org.apache.maven.shared.transfer.project.deploy.ProjectDeployer;

import java.io.File;
import java.io.IOException;

/**
 * Goal for bulk import into remote repository
 */
@Mojo( name = "bulk-import", requiresProject = false, threadSafe = true )
public class BulkImportMojo extends AbstractMojo {
     /**
     * @parameter default-value="${project}"
     * @required
     * @readonly
     */
    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    MavenProject project;

    @Parameter(defaultValue = "${localRepository}", readonly = true, required = true)
    ArtifactRepository localRepository;

    /**
     * Server Id to map on the &lt;id&gt; under &lt;server&gt; section of settings.xml In most cases, this parameter
     * will be required for authentication.
     */
    @Parameter( property = "repositoryId", defaultValue = "remote-repository", required = true )
    private String repositoryId;

    /**
     * URL where the artifact will be deployed. <br/>
     * ie ( file:///C:/m2-repo or scp://host.com/path/to/repo )
     */
    @Parameter( property = "repositoryUrl", required = true )
    private String repositoryUrl;

    /**
     * Alternative location to upload artifacts from.  This directory must be in
     * the same format as an maven2 local repository.
     */
    @Parameter( property = "repositoryBase", required = false )
    private File repositoryBase;

    @Parameter( defaultValue = "${session}", readonly = true, required = true )
    private MavenSession session;

    /**
     * This parameter can be used to control whether or not to only allow snapshots, releases or both to be uploaded
     * to the nexus repository
     */
    @Parameter( defaultValue = "RELEASE_ONLY", required = true )
    private DeploymentType deploymentType;

    @Component
    private ArtifactDeployer artifactDeployer;

    /**
     * Used for attaching the artifacts to deploy to the project.
     */
    @Component
    private MavenProjectHelper projectHelper;

    /**
     * Used for creating the project to which the artifacts to deploy will be attached.
     */
    @Component
    private ProjectBuilder projectBuilder;

    /**
     * Component used to deploy project.
     */
    @Component
    private ProjectDeployer projectDeployer;

    /**
     * Location of the file.
     */
    @Parameter( defaultValue = "${project.build.directory}", property = "outputDir", required = true )
    private File outputDirectory;

    public void execute() throws MojoExecutionException {
        BulkUploader bulkUploader = new BulkUploader.Builder()
                .setDeploymentType(deploymentType)
                .setRepositoryDirectory(repositoryBase != null ? repositoryBase : new File(localRepository.getBasedir()))
                .setArtifactRepository(createDeploymentArtifactRepository(repositoryId, repositoryUrl))
                .setProjectDeployer(projectDeployer)
                .setProjectBuilder(projectBuilder)
                .setMavenSession(session)
                .setProjectHelper(projectHelper)
                .setArtifactDeployer(artifactDeployer)
                .setLogger(getLog())
                .build();

        try {
            bulkUploader.execute();
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    protected ArtifactRepository createDeploymentArtifactRepository( String id, String url )
    {
        return new MavenArtifactRepository( id, url, new DefaultRepositoryLayout(), new ArtifactRepositoryPolicy(),
                new ArtifactRepositoryPolicy() );
    }

}
