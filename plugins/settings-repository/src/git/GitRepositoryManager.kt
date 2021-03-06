/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.settingsRepository.git

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.util.ShutDownTracker
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.SmartList
import org.eclipse.jgit.api.AddCommand
import org.eclipse.jgit.errors.TransportException
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.*
import org.jetbrains.jgit.dirCache.AddLoadedFile
import org.jetbrains.jgit.dirCache.DeleteDirectory
import org.jetbrains.jgit.dirCache.deletePath
import org.jetbrains.jgit.dirCache.edit
import org.jetbrains.keychain.CredentialsStore
import org.jetbrains.settingsRepository.AuthenticationException
import org.jetbrains.settingsRepository.BaseRepositoryManager
import org.jetbrains.settingsRepository.LOG
import org.jetbrains.settingsRepository.RepositoryManager.Updater
import org.jetbrains.settingsRepository.RepositoryService
import java.io.File
import java.io.IOException
import kotlin.concurrent.write
import kotlin.properties.Delegates

class GitRepositoryManager(private val credentialsStore: NotNullLazyValue<CredentialsStore>, dir: File) : BaseRepositoryManager(dir) {
  val repository: Repository
    get() {
      var r = _repository
      if (r == null) {
        r = FileRepositoryBuilder().setWorkTree(dir).build()
        _repository = r
      }
      return r!!
    }

  // we must recreate repository if dir changed because repository stores old state and cannot be reinitialized (so, old instance cannot be reused and we must instantiate new one)
  var _repository: Repository? = null

  val credentialsProvider: CredentialsProvider by Delegates.lazy {
    JGitCredentialsProvider(credentialsStore, repository)
  }

  init {
    if (ApplicationManager.getApplication()?.isUnitTestMode() != true) {
      ShutDownTracker.getInstance().registerShutdownTask(object: Runnable {
        override fun run() {
          _repository?.close()
        }
      })
    }
  }

  override fun createRepositoryIfNeed(): Boolean {
    if (isRepositoryExists()) {
      return false
    }

    repository.create()
    repository.disableAutoCrLf()
    return true
  }

  override fun deleteRepository() {
    super.deleteRepository()

    val r = _repository
    if (r != null) {
      _repository = null
      r.close()
    }
  }

  override fun getUpstream(): String? {
    return StringUtil.nullize(repository.getConfig().getString(ConfigConstants.CONFIG_REMOTE_SECTION, Constants.DEFAULT_REMOTE_NAME, ConfigConstants.CONFIG_KEY_URL))
  }

  override fun setUpstream(url: String?, branch: String?) {
    repository.setUpstream(url, branch ?: Constants.MASTER)
  }

  override fun isRepositoryExists() = repository.getObjectDatabase().exists()

  override fun hasUpstream() = getUpstream() != null

  override fun addToIndex(file: File, path: String, content: ByteArray, size: Int) {
    repository.edit(AddLoadedFile(path, content, size, file.lastModified()))
  }

  override fun deleteFromIndex(path: String, isFile: Boolean) {
    repository.deletePath(path, isFile, false)
  }

  override fun commit(indicator: ProgressIndicator?) = lock.write { commit(this, indicator) }

  override fun getAheadCommitsCount() = repository.getAheadCommitsCount()

  override fun commit(paths: List<String>) {
  }

  override fun push(indicator: ProgressIndicator?) {
    LOG.debug("Push")

    val refSpecs = SmartList(RemoteConfig(repository.getConfig(), Constants.DEFAULT_REMOTE_NAME).getPushRefSpecs())
    if (refSpecs.isEmpty()) {
      val head = repository.getRef(Constants.HEAD)
      if (head != null && head.isSymbolic()) {
        refSpecs.add(RefSpec(head.getLeaf().getName()))
      }
    }

    val monitor = indicator.asProgressMonitor()
    for (transport in Transport.openAll(repository, Constants.DEFAULT_REMOTE_NAME, Transport.Operation.PUSH)) {
      for (attempt in 0..1) {
        transport.setCredentialsProvider(credentialsProvider)
        try {
          val result = transport.push(monitor, transport.findRemoteRefUpdatesFor(refSpecs))
          if (LOG.isDebugEnabled()) {
            printMessages(result)

            for (refUpdate in result.getRemoteUpdates()) {
              LOG.debug(refUpdate.toString())
            }
          }
          break;
        }
        catch (e: TransportException) {
          if (e.getStatus() == TransportException.Status.NOT_PERMITTED) {
            if (attempt == 0) {
              credentialsProvider.reset(transport.getURI())
            }
            else {
              throw AuthenticationException(e)
            }
          }
          else {
            wrapIfNeedAndReThrow(e)
          }
        }
        finally {
          transport.close()
        }
      }
    }
  }

  override fun fetch(indicator: ProgressIndicator?): Updater {
    val pullTask = Pull(this, indicator ?: EmptyProgressIndicator())
    val refToMerge = pullTask.fetch()
    return object : Updater {
      override var definitelySkipPush = false

      override fun merge() = lock.write {
        val committed = commit(pullTask.indicator)
        if (refToMerge == null && !committed && getAheadCommitsCount() == 0) {
          definitelySkipPush = true
          return null
        }
        else {
          return pullTask.pull(prefetchedRefToMerge = refToMerge)
        }
      }
    }
  }

  override fun pull(indicator: ProgressIndicator) = Pull(this, indicator).pull()

  override fun resetToTheirs(indicator: ProgressIndicator) = Reset(this, indicator).reset(true)

  override fun resetToMy(indicator: ProgressIndicator, localRepositoryInitializer: (() -> Unit)?) = Reset(this, indicator).reset(false, localRepositoryInitializer)

  override fun canCommit() = repository.getRepositoryState().canCommit()

  fun renameDirectory(pairs: Map<String, String?>) {
    val addCommand = AddCommand(repository)
    val toDelete = SmartList<DeleteDirectory>()
    var added = false
    for ((oldPath, newPath) in pairs) {
      val old = File(dir, oldPath)
      if (!old.exists()) {
        continue
      }

      LOG.info("Rename $oldPath to $newPath")

      val files = old.listFiles()
      if (files != null) {
        val new = if (newPath == null) dir else File(dir, newPath)
        for (file in files) {
          try {
            if (file.isHidden()) {
              FileUtil.delete(file)
            }
            else {
              file.renameTo(File(new, file.getName()))
              addCommand.addFilepattern(if (newPath == null) file.getName() else "$newPath/${file.getName()}")
              added = true
            }
          }
          catch (e: Throwable) {
            LOG.error(e)
          }
        }
        toDelete.add(DeleteDirectory(oldPath))
      }

      try {
        FileUtil.delete(old)
      }
      catch (e: Throwable) {
        LOG.error(e)
      }
    }

    repository.edit(toDelete)
    if (added) {
      addCommand.call()
    }

    if (toDelete.isEmpty() && !added) {
      return
    }

    repository.commit(IdeaCommitMessageFormatter().prependMessage().append("Get rid of \$ROOT_CONFIG$").toString())
  }
}

fun printMessages(fetchResult: OperationResult) {
  if (LOG.isDebugEnabled()) {
    val messages = fetchResult.getMessages()
    if (!StringUtil.isEmptyOrSpaces(messages)) {
      LOG.debug(messages)
    }
  }
}

class GitRepositoryService : RepositoryService {
  override fun isValidRepository(file: File): Boolean {
    if (File(file, Constants.DOT_GIT).exists()) {
      return true
    }

    // existing bare repository
    try {
      FileRepositoryBuilder().setGitDir(file).setMustExist(true).build()
    }
    catch (e: IOException) {
      return false
    }

    return true
  }
}