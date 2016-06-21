package com.google.apphosting.vmruntime.jetty9;

import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.PathAssert;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;

/**
 * Environment for IT tests.
 * <p>
 * Specifically designed to establish environment for IT tests without initializing anything
 * that the IT test cases need (such as System Properties and/or Logging)
 * </p>
 */
public class IntegrationEnv {

  /**
   * Create a new copy of the target/jetty-base directory for testing purposes.
   * <p>
   * We don't want to modify the target/jetty-base directory directly with
   * our unit tests.
   * </p>
   *
   * @return the copied jetty-base directory location.
   * @throws IOException if unable to create the copied directory
   */
  public static Path getJettyBase() throws IOException {
    // Find source / test
    Path readOnlyJettyBase = MavenTestingUtils.getTargetPath("jetty-base");
    PathAssert.assertDirExists("jetty.base", readOnlyJettyBase);

    // Find test / copy of jetty-base
    Path testJettyBase = MavenTestingUtils.getTargetTestingPath("it-jetty-base");
    if (Files.exists(testJettyBase) || !Files.isDirectory(testJettyBase)) {
      FS.ensureEmpty(testJettyBase);
      copyDirectory(readOnlyJettyBase, testJettyBase, 5);
    }

    System.setProperty("jetty.base", testJettyBase.toString());

    return testJettyBase;
  }

  public static void copyDirectory(Path src, Path dest, int depth) throws IOException {
    Path absSrc = src.toAbsolutePath();
    Path absDest = dest.toAbsolutePath();
    System.err.printf("## Copy Dir %s -> %s%n", absSrc, absDest);
    EnumSet<FileVisitOption> opts = EnumSet.of(FileVisitOption.FOLLOW_LINKS);
    FileSystemCopier copier = new FileSystemCopier(absSrc, absDest);
    Files.walkFileTree(absSrc, opts, depth, copier);
  }

  private static class FileSystemCopier implements FileVisitor<Path> {
    private final Path src;
    private final Path dest;
    private final CopyOption[] copyDirOpts = new CopyOption[] {COPY_ATTRIBUTES};
    private final CopyOption[] copyFileOpts = new CopyOption[] {COPY_ATTRIBUTES, REPLACE_EXISTING};

    public FileSystemCopier(Path src, Path dest) {
      this.src = src;
      this.dest = dest;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
        throws IOException {
      Path newDir = dest.resolve(src.relativize(dir));

      try {
        Files.copy(dir, newDir, copyDirOpts);
      } catch (FileAlreadyExistsException ignore) {
        /* ignore */
      }
      return CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
      Path destFile = dest.resolve(src.relativize(file));
      Files.copy(file, destFile, copyFileOpts);
      return CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
      System.err.printf("## FAILURE: " + file + " | ");
      exc.printStackTrace(System.err);
      if (exc instanceof FileSystemLoopException) {
        throw exc;
      }
      return CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
      return CONTINUE;
    }
  }
}
