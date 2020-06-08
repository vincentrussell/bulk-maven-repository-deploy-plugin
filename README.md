# bulk-maven-repository-deploy-plugin [![Maven Central](https://img.shields.io/maven-central/v/com.github.vincentrussell/bulk-maven-repository-deploy-plugin.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22com.github.vincentrussell%22%20AND%20a:%22bulk-maven-repository-deploy-plugin%22) [![Build Status](https://travis-ci.org/vincentrussell/bulk-maven-repository-deploy-plugin.svg?branch=master)](https://travis-ci.org/vincentrussell/bulk-maven-repository-deploy-plugin)

bulk-maven-repository-deploy-plugin will take a lock maven2 repo and bulk upload the artifacts into a remote maven repository.  You can upload from your local m2 repository or another directory that is in the same format.

## Maven

Add a dependency to `com.github.vincentrussell:bulk-maven-repository-deploy-plugin`.

```
<dependency>
   <groupId>com.github.vincentrussell</groupId>
   <artifactId>bulk-maven-repository-deploy-plugin</artifactId>
   <version>1.0</version>
</dependency>
```

## Requirements
- JDK 1.7 or higher

## Running from the command line

  The easiest way to use this plugin is to just use it from the command line.
```
mvn com.github.vincentrussell:bulk-maven-repository-deploy-plugin:1.0:bulk-import -DrepositoryId=thirdparty -DrepositoryUrl=http://localhost:8081/repository/thirdparty/
```
| Option | Description  |
|--|--|
| repositoryId | Server Id to map on the &lt;id&gt; under &lt;server&gt; section of settings.xml In most cases, this parameter will be required for authentication. |
| repositoryUrl | URL where the artifact will be deployed. (i.e: http://localhost:8081/repository/thirdparty/) |
| repositoryBase | Alternative location to upload artifacts from.  This directory must be in the same format as an maven2 local repository  |
| deploymentType | This parameter can be used to control whether or not to only allow snapshots, releases or both to be uploaded to the nexus repository.  The options are SNAPSHOT_ONLY, RELEASE_ONLY, or SNAPSHOT_AND_RELEASE.  The default value is RELEASE_ONLY  |


# Change Log

## [1.0](https://github.com/vincentrussell/bulk-maven-repository-deploy-plugin/tree/bulk-maven-repository-deploy-plugin-1.0) (2020-06-11)

**Enhancements:**

- Initial Release

