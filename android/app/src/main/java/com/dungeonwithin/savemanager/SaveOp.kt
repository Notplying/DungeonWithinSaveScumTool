package com.dungeonwithin.savemanager

/** Backup vs restore, as a domain type instead of a Boolean flag. */
enum class SaveOp {
    BACKUP,
    RESTORE;

    fun execute(repo: SaveRepository): SaveRepository.Outcome =
        when (this) {
            BACKUP -> repo.doBackup()
            RESTORE -> repo.doRestore()
        }

    /** Short label for toasts and status lines ("Backup OK", "Restore failed"). */
    val label: String
        get() = when (this) {
            BACKUP -> "Backup"
            RESTORE -> "Restore"
        }
}
