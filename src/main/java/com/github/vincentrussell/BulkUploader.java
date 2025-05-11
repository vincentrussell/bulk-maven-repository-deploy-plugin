package com.github.vincentrussell;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.building.StringModelSource;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.*;
import org.apache.maven.shared.transfer.artifact.deploy.ArtifactDeployer;
import org.apache.maven.shared.transfer.artifact.deploy.ArtifactDeployerException;
import org.apache.maven.shared.transfer.project.NoFileAssignedException;
import org.apache.maven.shared.transfer.project.deploy.ProjectDeployer;
import org.apache.maven.shared.transfer.project.deploy.ProjectDeployerRequest;
import org.apache.maven.shared.utils.Os;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.apache.commons.lang3.Validate.isTrue;
import static org.apache.commons.lang3.Validate.notNull;

public class BulkUploader {
    private final ArtifactRepository artifactRepository;
    private final ProjectDeployer projectDeployer;
    private final ProjectBuilder projectBuilder;
    private final MavenSession mavenSession;
    private final MavenProjectHelper projectHelper;
    private final ArtifactDeployer artifactDeployer;
    private final DeploymentType deploymentType;
    private final Log log;
    private final File repositoryDirectory;
    private final String repositorySubDirectory;
    private final String groupId;
    private final String artifactid;

    private BulkUploader(final Builder builder) {
        this.repositoryDirectory = builder.repositoryDirectory;
        this.repositorySubDirectory = builder.repositorySubDirectory;
        this.groupId = builder.groupId;
        this.artifactid = builder.artifactId;
        this.artifactRepository = builder.artifactRepository;
        this.projectDeployer = builder.projectDeployer;
        this.projectBuilder = builder.projectBuilder;
        this.mavenSession = builder.mavenSession;
        this.projectHelper = builder.projectHelper;
        this.artifactDeployer = builder.artifactDeployer;
        this.deploymentType = builder.deploymentType;
        this.log = builder.log;
    }

    public boolean execute() throws IOException {
        notNull(repositoryDirectory, "repositoryDirectory is null");
        isTrue(repositoryDirectory.exists(), "%s does not exit directory", repositoryDirectory.getAbsolutePath());
        isTrue(repositoryDirectory.isDirectory(), "%s is not a directory", repositoryDirectory.getAbsolutePath());
        notNull(artifactRepository, "artifactRepository is null");
        notNull(projectDeployer, "projectDeployer is null");
        notNull(projectBuilder, "projectBuilder is null");
        notNull(mavenSession, "mavenSession is null");
        notNull(projectHelper, "projectHelper is null");
        notNull(artifactDeployer, "artifactDeployer is null");
        notNull(deploymentType, "repositoryType is null");


        String protocol = artifactRepository.getProtocol();
        if (StringUtils.isEmpty(protocol)) {
            throw new IOException("No transfer protocol found.");
        }

        File artifactsPath;
        if (StringUtils.isNotBlank(repositorySubDirectory)) {
            artifactsPath = new File(repositoryDirectory + File.separator + repositorySubDirectory);
        } else {
            artifactsPath = repositoryDirectory;
        }

        List<File> artifactFiles;
        try (Stream<Path> walk = Files.walk(artifactsPath.toPath())) {
            artifactFiles = walk.map(Path::toFile).toList();
        }

        for (final File file : artifactFiles) {
            if (file.isFile()) {
                Artifact artifact = getArtifact(file);
                if (artifact != null) {
                    boolean isSnapshot = ArtifactUtils.isSnapshot(artifact.getVersion());

                    //skip sha1 and md5 for snapshots
                    if (isSnapshot && isHashFile(artifact.getType())) {
                        continue;
                    }

                    if (!DeploymentType.SNAPSHOT_AND_RELEASE.equals(deploymentType)
                            && isSnapshot && DeploymentType.RELEASE_ONLY.equals(deploymentType)) {
                        log.info(String.format("artifact %s is considered to be a snapshot and will not be deployed", artifact));
                        continue;
                    } else if (!DeploymentType.SNAPSHOT_AND_RELEASE.equals(deploymentType)
                            && !isSnapshot && DeploymentType.SNAPSHOT_ONLY.equals(deploymentType)) {
                        log.info(String.format("artifact %s is considered to be a release and will not be deployed", artifact));
                        continue;
                    }

                    if (StringUtils.isNotBlank(groupId) && !artifact.getGroupId().equals(groupId)) {
                        log.info(String.format("artifact group %s does not match required group %s",
                                artifact.getGroupId(), groupId));
                        continue;
                    }

                    if (StringUtils.isNotBlank(artifactid) && !artifact.getArtifactId().equals(artifactid)) {
                        log.info(String.format("artifact %s does not match required group %s",
                                artifact.getArtifactId(), artifactid));
                        continue;
                    }

                    MavenProject project = createMavenProject(artifact);
                    List<Artifact> deployableArtifacts = new ArrayList<>();

                    if (artifact.getClassifier() == null) {
                        artifact.setFile(file);
                        deployableArtifacts.add(artifact);
                    } else {
                        projectHelper.attachArtifact(project, artifact.getType(), artifact.getClassifier(), file);
                    }

                    List<Artifact> attachedArtifacts = project.getAttachedArtifacts();
                    deployableArtifacts.addAll(attachedArtifacts);

                    try {
                        artifactDeployer.deploy(mavenSession.getProjectBuildingRequest(), artifactRepository,
                                deployableArtifacts);
                    } catch (ArtifactDeployerException e) {
                        if (e.getMessage().contains("Repository does not allow updating assets")) {
                            log.error(String.format("artifact %s failed deployment because it already exists in repo",
                                    artifact));
                            continue;
                        } else {
                            log.error(String.format("artifact %s deployment failed because %s", artifact, e.getMessage()));
                        }
                        throw new IOException(e);
                    }
                    log.info(String.format("artifact %s deployed successfully", artifact));
                }
            }
        }
        return true;
    }

    private boolean isHashFile(final String type) {
        return type != null && (type.endsWith("sha1") || type.endsWith("md5"));
    }

    private Artifact getArtifact(final File file) {
        try {
            final File parentDir = file.getParentFile();
            final File artifactIdDirectory = parentDir.getParentFile();
            final String version = parentDir.getName();
            final String artifactId = artifactIdDirectory.getName();

            final Pattern artifactPattern = Pattern.compile("^" + artifactId
                    + "-" + version + "-{0,1}([^.][\\S]+?){0,1}\\.(\\S+){1}$");

            final Matcher matcher = artifactPattern.matcher(file.getName());
            if (matcher.matches()) {

                final String classifier = matcher.group(1);
                final String extension = matcher.group(2);

                final String groupId = artifactIdDirectory.getParentFile().toPath().toString()
                        .replaceAll(repositoryDirectory.toPath().toString(), "")
                        .substring(1).replaceAll("/", ".");

                Artifact artifact = new DefaultArtifact(groupId, artifactId, version, "runtime",
                        extension, classifier, new DefaultArtifactHandler(extension));
                Objects.requireNonNull(artifact.getType());
                return artifact;
            } else {
                return null;
            }
        } catch (Exception t) {
            return null;
        }
    }

    private MavenProject createMavenProject(Artifact artifact) throws IOException {

        ModelSource modelSource = new StringModelSource("""
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>%s</groupId>
          <artifactId>%s</artifactId>
          <version>%s</version>
          <packaging>%s</packaging>
        </project>
        """.formatted(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getVersion(),
                artifact.getType()
        ));

        DefaultProjectBuildingRequest buildingRequest =
                new DefaultProjectBuildingRequest(mavenSession.getProjectBuildingRequest());
        buildingRequest.setProcessPlugins(false);
        try {
            return projectBuilder.build(modelSource, buildingRequest).getProject();
        } catch (ProjectBuildingException e) {
            if (e.getCause() instanceof ModelBuildingException) {
                throw new IOException("The artifact information is not valid:" + Os.LINE_SEP
                        + e.getCause().getMessage());
            }
            throw new IOException("Unable to create the project.", e);
        }
    }

    private void deployProject(ProjectBuildingRequest pbr, ProjectDeployerRequest pir, ArtifactRepository repo)
            throws IOException {
        try {
            projectDeployer.deploy(pbr, pir, repo);
        } catch (NoFileAssignedException e) {
            throw new IOException("NoFileAssignedException", e);
        } catch (ArtifactDeployerException e) {
            throw new IOException("ArtifactDeployerException", e);
        }
    }

    public static class Builder {
        private File repositoryDirectory = Paths.get(System.getProperty("user.home"),
                ".m2", "repository").toFile();
        private String repositorySubDirectory;
        private ArtifactRepository artifactRepository;
        private ProjectDeployer projectDeployer;
        private ProjectBuilder projectBuilder;
        private MavenSession mavenSession;
        private MavenProjectHelper projectHelper;
        private ArtifactDeployer artifactDeployer;
        private DeploymentType deploymentType = DeploymentType.RELEASE_ONLY;
        private Log log;
        private String groupId;
        private String artifactId;

        public Builder setRepositoryDirectory(final File repositoryDirectory) {
            this.repositoryDirectory = repositoryDirectory;
            return this;
        }

        public Builder setRepositorySubDirectory(final String repositorySubDirectory) {
            this.repositorySubDirectory = repositorySubDirectory;
            return this;
        }

        public Builder setArtifactRepository(ArtifactRepository artifactRepository) {
            this.artifactRepository = artifactRepository;
            return this;
        }

        public Builder setProjectDeployer(ProjectDeployer projectDeployer) {
            this.projectDeployer = projectDeployer;
            return this;
        }


        public Builder setProjectBuilder(ProjectBuilder projectBuilder) {
            this.projectBuilder = projectBuilder;
            return this;
        }

        public Builder setMavenSession(MavenSession mavenSession) {
            this.mavenSession = mavenSession;
            return this;
        }

        public Builder setProjectHelper(MavenProjectHelper projectHelper) {
            this.projectHelper = projectHelper;
            return this;
        }

        public Builder setArtifactDeployer(ArtifactDeployer artifactDeployer) {
            this.artifactDeployer = artifactDeployer;
            return this;
        }

        public Builder setLogger(Log log) {
            this.log = log;
            return this;
        }

        public Builder setDeploymentType(DeploymentType deploymentType) {
            this.deploymentType = deploymentType;
            return this;
        }

        public BulkUploader build() {
            return new BulkUploader(this);
        }

        public Builder setGroupId(String groupId) {
            this.groupId = groupId;
            return this;
        }

        public Builder setArtifactId(String artifactId) {
            this.artifactId = artifactId;
            return this;
        }
    }
}