package com.google.apphosting.vmruntime.jetty9;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.PathAssert;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Environment for IT tests.
 * <p>
 * Specifically designed to establish environment for IT tests without initializing anything
 * that the IT test cases need (such as System Properties and/or Logging)
 * </p>
 */
public class IntegrationEnv {
  public static Path getJettyBase() throws IOException {
    Path jettyBase = MavenTestingUtils.getTargetPath("jetty-base");
    if (System.getProperty("jetty.base") != null) {
      jettyBase = new File(System.getProperty("jetty.base")).toPath().toRealPath();
    }

    PathAssert.assertDirExists("jetty.base", jettyBase);
    return jettyBase;
  }
}
