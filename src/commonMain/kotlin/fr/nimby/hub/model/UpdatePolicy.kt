package fr.nimby.hub.model

/** A generation invalidates work started before a mode change, even after a quick on/off. */
class UpdatePolicy(developerMode: Boolean = false, automatic: Boolean = true) {
    var developerMode = developerMode
        private set
    var automatic = automatic
        private set
    var generation: Long = 0
        private set
    val canSynchronize get() = true
    val canAutoInstall get() = !developerMode && automatic
    fun change(developerMode: Boolean = this.developerMode, automatic: Boolean = this.automatic) {
        if (this.developerMode != developerMode || this.automatic != automatic) generation++
        this.developerMode = developerMode
        this.automatic = automatic
    }
    fun accepts(ticket: Long) = ticket == generation && canSynchronize
    fun invalidate() { generation++ }
}
