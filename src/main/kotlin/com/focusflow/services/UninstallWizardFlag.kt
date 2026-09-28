package com.focusflow.services

/** Private command-line entry point for the guarded uninstall handoff. */
object UninstallWizardFlag {
    fun isRequested(args: Array<String>): Boolean =
        args.any { it.equals("--uninstall", ignoreCase = true) }
}