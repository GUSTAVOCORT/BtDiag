package com.carplayer.btdiag

import android.service.notification.NotificationListenerService

/**
 * Servicio vacio a proposito. Android solo permite usar
 * MediaSessionManager.getActiveSessions() a una app que tenga un
 * NotificationListenerService habilitado por el usuario.
 */
class NotifListener : NotificationListenerService()
