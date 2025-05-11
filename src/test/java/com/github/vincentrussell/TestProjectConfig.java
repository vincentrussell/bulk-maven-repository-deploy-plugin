package com.github.vincentrussell;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class TestProjectConfig {
    private static final String TEXT;
    private final TemporaryFolder temporaryFolder;

    static {
        try {
            TEXT = IOUtils.toString(TestProjectConfig.class.getResourceAsStream("/test-project-config-pom.xml"),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public TestProjectConfig(TemporaryFolder temporaryFolder) {
        this.temporaryFolder = temporaryFolder;
    }

    public File getFile(final String config) throws IOException {
        File folder = temporaryFolder.newFolder("newProject");
        File newFile = new File(folder, "pom.xml");
        FileUtils.write(newFile, TEXT.replaceAll("REPLACE_ME", config), StandardCharsets.UTF_8);
        return newFile;
    }
}
