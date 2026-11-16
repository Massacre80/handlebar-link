package com.abid.handlebarlink.controls

import android.service.notification.NotificationListenerService

/**
 * Exists only so the app may find CarLink's media session (MediaSessionManager.getActiveSessions needs
 * an enabled notification listener). Notifications themselves are ignored. Interim: deleted when our
 * own CarPlay engine receives presses in-process (research 01 §2.4).
 */
class MediaAccessListener : NotificationListenerService()
