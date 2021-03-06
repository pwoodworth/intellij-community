package com.jetbrains.env;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.vfs.VfsUtil;
import com.jetbrains.python.psi.LanguageLevel;
import com.jetbrains.python.sdk.InvalidSdkException;
import com.jetbrains.python.sdk.PythonSdkType;
import com.jetbrains.python.sdkTools.PyTestSdkTools;
import com.jetbrains.python.sdkTools.SdkCreationType;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Set;

/**
 * @author traff
 */
public class PyEnvTaskRunner {
  private final List<String> myRoots;

  public PyEnvTaskRunner(List<String> roots) {
    myRoots = roots;
  }

  public void runTask(PyTestTask testTask, String testName) {
    boolean wasExecuted = false;

    List<String> passedRoots = Lists.newArrayList();

    for (String root : myRoots) {

      if (!isSuitableForTask(PyEnvTestCase.loadEnvTags(root), testTask) || !shouldRun(root, testTask)) {
        continue;
      }

      try {
        testTask.setUp(testName);
        wasExecuted = true;
        if (isJython(root)) {
          testTask.useLongTimeout();
        }
        else {
          testTask.useNormalTimeout();
        }
        final String executable = getExecutable(root, testTask);
        if (executable == null) {
          throw new RuntimeException("Cannot find Python interpreter in " + root);
        }
        final Sdk sdk = createSdkByExecutable(executable);

        /**
         * Skipping test if {@link PyTestTask} reports it does not support this language level
         */
        final LanguageLevel languageLevel = PythonSdkType.getLanguageLevelForSdk(sdk);
        if (testTask.isLanguageLevelSupported(languageLevel)) {
          testTask.runTestOn(executable);
          passedRoots.add(root);
        }
        else {
          System.err.println(String.format("Skipping root %s", root));
        }
      }
      catch (Throwable e) {
        throw new RuntimeException(
          PyEnvTestCase.joinStrings(passedRoots, "Tests passed environments: ") + "Test failed on " + getEnvType() + " environment " + root,
          e);
      }
      finally {
        try {
          testTask.tearDown();
        }
        catch (Exception e) {
          throw new RuntimeException("Couldn't tear down task", e);
        }
      }
    }

    if (!wasExecuted) {
      throw new RuntimeException("test" +
                                 testName +
                                 " was not executed.\n" +
                                 PyEnvTestCase.joinStrings(myRoots, "All roots: ") +
                                 "\n" +
                                 PyEnvTestCase.joinStrings(testTask.getTags(), "Required tags in tags.txt in root: "));
    }
  }

  /**
   * Create SDK by path to python exectuable
   *
   * @param executable path executable
   * @return sdk
   * @throws InvalidSdkException bad sdk
   * @throws IOException         bad path
   */
  @NotNull
  private static Sdk createSdkByExecutable(@NotNull final String executable) throws InvalidSdkException, IOException {
    final URL rootUrl = new URL(String.format("file:///%s", executable));
    return PyTestSdkTools.createTempSdk(VfsUtil.findFileByURL(rootUrl), SdkCreationType.SDK_PACKAGES_AND_SKELETONS, null);
  }

  protected boolean shouldRun(String root, PyTestTask task) {
    return true;
  }

  protected String getExecutable(String root, PyTestTask testTask) {
    return PythonSdkType.getPythonExecutable(root);
  }

  protected String getEnvType() {
    return "local";
  }

  private static boolean isSuitableForTask(List<String> tags, PyTestTask task) {
    return isSuitableForTags(tags, task.getTags());
  }

  public static boolean isSuitableForTags(List<String> envTags, Set<String> taskTags) {
    Set<String> necessaryTags = Sets.newHashSet(taskTags);

    for (String tag : envTags) {
      necessaryTags.remove(tag.trim());
    }

    for (String tag : taskTags) {
      if (tag.startsWith("-")) { //do not run on envs with that tag
        if (envTags.contains(tag.substring(1))) {
          return false;
        }
        necessaryTags.remove(tag);
      }
    }

    return necessaryTags.isEmpty();
  }


  public static boolean isJython(@NotNull String sdkHome) {
    return sdkHome.toLowerCase().contains("jython");
  }
}
