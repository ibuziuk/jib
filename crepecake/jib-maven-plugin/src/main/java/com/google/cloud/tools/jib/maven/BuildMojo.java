/*
 * Copyright 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.maven;

import com.google.cloud.tools.crepecake.builder.BuildConfiguration;
import com.google.cloud.tools.crepecake.builder.BuildImageSteps;
import com.google.cloud.tools.crepecake.builder.SourceFilesConfiguration;
import com.google.cloud.tools.crepecake.cache.CacheMetadataCorruptedException;
import com.google.cloud.tools.crepecake.image.DuplicateLayerException;
import com.google.cloud.tools.crepecake.image.LayerCountMismatchException;
import com.google.cloud.tools.crepecake.image.LayerPropertyNotFoundException;
import com.google.cloud.tools.crepecake.registry.NonexistentDockerCredentialHelperException;
import com.google.cloud.tools.crepecake.registry.NonexistentServerUrlDockerCredentialHelperException;
import com.google.cloud.tools.crepecake.registry.RegistryAuthenticationFailedException;
import com.google.cloud.tools.crepecake.registry.RegistryException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/** Builds a container image. */
@Mojo(name = "build")
public class BuildMojo extends AbstractMojo {

  private static class MavenSourceFilesConfiguration implements SourceFilesConfiguration {

    private final Set<Path> dependenciesFiles = new HashSet<>();
    private final Set<Path> resourcesFiles = new HashSet<>();
    private final Set<Path> classesFiles = new HashSet<>();

    private MavenSourceFilesConfiguration(MavenProject project) throws IOException {
      Path classesOutputDir = Paths.get(project.getBuild().getOutputDirectory());

      for (Dependency dependency : project.getDependencies()) {
        dependenciesFiles.add(Paths.get(dependency.getSystemPath()));
      }

      for (Resource resource : project.getResources()) {
        Path resourceSourceDir = Paths.get(resource.getDirectory());
        Files.list(resourceSourceDir)
            .forEach(
                resourceSourceDirFIle -> {
                  Path correspondingOutputDirFile =
                      classesOutputDir.resolve(resourceSourceDir.relativize(resourceSourceDirFIle));
                  if (Files.exists(correspondingOutputDirFile)) {
                    resourcesFiles.add(correspondingOutputDirFile);
                  }
                });
      }

      Path classesSourceDir = Paths.get(project.getBuild().getSourceDirectory());

      Files.list(classesSourceDir)
          .forEach(
              classesSourceDirFile -> {
                Path correspondingOutputDirFile =
                    classesOutputDir.resolve(classesSourceDir.relativize(classesSourceDirFile));
                if (Files.exists(correspondingOutputDirFile)) {
                  classesFiles.add(correspondingOutputDirFile);
                }
              });

      // TODO: Check if there are still unaccounted-for files in the runtime classpath.
    }

    @Override
    public Set<Path> getDependenciesFiles() {
      return dependenciesFiles;
    }

    @Override
    public Set<Path> getResourcesFiles() {
      return resourcesFiles;
    }

    @Override
    public Set<Path> getClassesFiles() {
      return classesFiles;
    }

    @Override
    public Path getDependenciesExtractionPath() {
      return null;
    }

    @Override
    public Path getResourcesExtractionPath() {
      return null;
    }

    @Override
    public Path getClassesExtractionPath() {
      return null;
    }
  }

  @Parameter(defaultValue = "${project}", readonly = true)
  private MavenProject project;

  @Override
  public void execute() throws MojoExecutionException {
    SourceFilesConfiguration sourceFilesConfiguration = getSourceFilesConfiguration();

    BuildConfiguration buildConfiguration =
        BuildConfiguration.builder()
            .setBaseImageServerUrl("gcr.io")
            .setBaseImageName("distroless/java")
            .setBaseImageTag("latest")
            .setTargetServerUrl("gcr.io")
            .setTargetImageName("jibtestimage")
            .setTargetTag("testtag")
            .setCredentialHelperName("gcr")
            .setMainClass("com.test.HelloWorld")
            .build();

    Path cacheDirectory = Paths.get(project.getBuild().getDirectory(), "jib-cache");
    if (!Files.exists(cacheDirectory)) {
      try {
        Files.createDirectory(cacheDirectory);

      } catch (IOException ex) {
        throw new MojoExecutionException("Could not create cache directory", ex);
      }
    }

    try {
      BuildImageSteps buildImageSteps =
          new BuildImageSteps(buildConfiguration, sourceFilesConfiguration, cacheDirectory);
      buildImageSteps.run();

    } catch (IOException
        | RegistryException
        | CacheMetadataCorruptedException
        | DuplicateLayerException
        | LayerPropertyNotFoundException
        | LayerCountMismatchException
        | NonexistentDockerCredentialHelperException
        | RegistryAuthenticationFailedException
        | NonexistentServerUrlDockerCredentialHelperException ex) {
      throw new MojoExecutionException("Build image failed", ex);
    }
  }

  private SourceFilesConfiguration getSourceFilesConfiguration() throws MojoExecutionException {
    try {
      SourceFilesConfiguration sourceFilesConfiguration =
          new MavenSourceFilesConfiguration(project);

      getLog().info("Dependencies:");
      sourceFilesConfiguration
          .getDependenciesFiles()
          .forEach(dependencyFile -> getLog().info("Dependency: " + dependencyFile));

      getLog().info("Resources:");
      sourceFilesConfiguration
          .getResourcesFiles()
          .forEach(resourceFile -> getLog().info("Resource: " + resourceFile));

      getLog().info("Classes:");
      sourceFilesConfiguration
          .getClassesFiles()
          .forEach(classesFile -> getLog().info("Class: " + classesFile));

      return sourceFilesConfiguration;

    } catch (IOException ex) {
      throw new MojoExecutionException("Obtaining project build output files failed", ex);
    }
  }
}
