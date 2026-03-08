package io.aldefy.rebound.ide.data

import com.intellij.openapi.project.Project

data class VcsSessionContext(
    val branch: String?,
    val commitHash: String?
) {
    companion object {
        fun capture(project: Project): VcsSessionContext {
            return try {
                val repoManagerClass = Class.forName("git4idea.repo.GitRepositoryManager")
                val getInstanceMethod = repoManagerClass.getMethod("getInstance", Project::class.java)
                val repoManager = getInstanceMethod.invoke(null, project)
                val getRepositoriesMethod = repoManagerClass.getMethod("getRepositories")
                @Suppress("UNCHECKED_CAST")
                val repositories = getRepositoriesMethod.invoke(repoManager) as? List<Any>
                val repo = repositories?.firstOrNull() ?: return VcsSessionContext(null, null)

                val repoClass = repo.javaClass
                val currentBranch = try {
                    val branchMethod = repoClass.getMethod("getCurrentBranch")
                    val branch = branchMethod.invoke(repo)
                    if (branch != null) {
                        val nameMethod = branch.javaClass.getMethod("getName")
                        nameMethod.invoke(branch) as? String
                    } else null
                } catch (_: Exception) { null }

                val currentRevision = try {
                    val revisionMethod = repoClass.getMethod("getCurrentRevision")
                    revisionMethod.invoke(repo) as? String
                } catch (_: Exception) { null }

                VcsSessionContext(
                    branch = currentBranch,
                    commitHash = currentRevision
                )
            } catch (e: Exception) {
                VcsSessionContext(null, null)
            }
        }
    }
}
