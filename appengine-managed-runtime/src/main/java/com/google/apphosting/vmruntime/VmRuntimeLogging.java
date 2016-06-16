/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.apphosting.vmruntime;

import com.google.apphosting.utils.config.AppEngineConfigException;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.ContextInitializer;
import ch.qos.logback.core.joran.spi.JoranException;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.slf4j.impl.StaticLoggerBinder;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;

public class VmRuntimeLogging {
  public static final String LOGGING_CONFIGURATION_KEY = "logback.configurationFile";

  public static void load(String logbackXmlFile) throws AppEngineConfigException {
    // Establish java.util.logging -> slf4j bridge
    if (!SLF4JBridgeHandler.isInstalled()) {
      SLF4JBridgeHandler.removeHandlersForRootLogger();
      SLF4JBridgeHandler.install();
    }

    LoggerContext rootContext =
        (LoggerContext) StaticLoggerBinder.getSingleton().getLoggerFactory();

    // Load user provided logback XML
    ContextInitializer initializer = new ContextInitializer(rootContext);

    if ((logbackXmlFile != null) && (logbackXmlFile.length() > 0)) {
      System.setProperty(LOGGING_CONFIGURATION_KEY, logbackXmlFile);
      try {
        initializer.autoConfig();
      } catch (JoranException e) {
        throw new AppEngineConfigException(e);
      }
    }

    // Load the system logback XML
    try {
      Enumeration<URL> configUrls =
          VmRuntimeLogging.class.getClass().getClassLoader().getResources("logback.xml");
      while (configUrls.hasMoreElements()) {
        URL url = configUrls.nextElement();
        if (url.toExternalForm().endsWith("resources/logback.xml")) {
          initializer.configureByResource(url);
        }
      }
    } catch (JoranException | IOException e) {
      throw new AppEngineConfigException(e);
    }
  }
}
