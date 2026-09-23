package com.dungeonwithin.savemanager

/**
 * Runs a shell script on the device and returns the completed result.
 *
 * Single seam between the backup/restore flow ([SaveRepository]) and the
 * privileged execution backend ([ShizukuShellExecutor] on-device, fakes in
 * tests). Scripts are the `sh -c` bodies built by [SavePaths].
 */
interface ShellExecutor {
    data class Result(val exitCode: Int, val stdout: String, val stderr: String)

    fun run(script: String, timeoutSeconds: Long = 30L): Result
}
