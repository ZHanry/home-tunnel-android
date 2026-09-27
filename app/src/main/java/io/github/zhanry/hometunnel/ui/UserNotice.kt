package io.github.zhanry.hometunnel.ui

import androidx.annotation.StringRes
import io.github.zhanry.hometunnel.R

@StringRes
internal fun userNoticeResource(code: String): Int = when (code) {
    "AUTH_INVALID" -> R.string.notice_auth_invalid
    "MFA_REQUIRED" -> R.string.notice_mfa_required
    "MFA_INVALID", "MFA_REPLAY" -> R.string.notice_mfa_invalid
    "USER_DISABLED" -> R.string.notice_user_disabled
    "DEVICE_REVOKED" -> R.string.notice_device_revoked
    "SESSION_REVOKED", "SESSION_EXPIRED", "UNAUTHORIZED" -> R.string.notice_session_expired
    "PASSWORD_CHANGE_REQUIRED" -> R.string.notice_password_required
    "PASSWORD_CHANGED" -> R.string.notice_password_changed
    "FORBIDDEN", "PERMISSION_DENIED" -> R.string.notice_forbidden
    "RATE_LIMITED" -> R.string.notice_rate_limited
    "VERSION_CONFLICT", "STATE_CONFLICT" -> R.string.notice_conflict
    "VALIDATION_ERROR", "PASSWORD_TOO_WEAK", "PASSWORD_POLICY" -> R.string.notice_validation
    "NOT_FOUND" -> R.string.notice_not_found
    "DEVICE_OFFLINE" -> R.string.notice_device_offline
    "QUOTA_EXCEEDED" -> R.string.notice_quota
    "TLS_ERROR" -> R.string.notice_tls
    "NETWORK_TIMEOUT" -> R.string.notice_timeout
    "NETWORK_ERROR" -> R.string.notice_network
    "DISCOVERY_INVALID_ORIGIN" -> R.string.notice_server_address
    "DISCOVERY_REDIRECT" -> R.string.notice_server_redirect
    "DISCOVERY_ORIGIN_MISMATCH" -> R.string.notice_server_identity
    "DISCOVERY_CONFIG_INVALID" -> R.string.notice_server_config
    "LOCAL_STATE_UNAVAILABLE" -> R.string.notice_local_state
    else -> R.string.notice_request_failed
}
