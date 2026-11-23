package com.levi.dock

import android.service.notification.NotificationListenerService

/**
 * Exists only so Android lets LEVI DOCK read media sessions
 * (MediaSessionManager requires an enabled notification listener).
 * It does not read or store notifications.
 */
class MediaListener : NotificationListenerService()
