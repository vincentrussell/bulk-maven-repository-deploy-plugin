package com.github.vincentrussell;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

public class TestProjectConfig {

    private final TemporaryFolder temporaryFolder;

    private static String TEXT = null;

    static {
        try {
            TEXT = IOUtils.toString(TestProjectConfig.class.getResourceAsStream("/test-project-config-pom.xml"), "UTF-8");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public TestProjectConfig(TemporaryFolder temporaryFolder) {
        this.temporaryFolder = temporaryFolder;
    }

    public File getFile(final String config) throws IOException {
        File folder = temporaryFolder.newFolder("newProject");
        File newFile = new File(folder, "pom.xml");
        FileUtils.write(newFile, TEXT.replaceAll("REPLACE_ME", config), "UTF-8");
        return newFile;
    }
}
