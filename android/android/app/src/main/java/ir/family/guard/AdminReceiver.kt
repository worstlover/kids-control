package ir.family.guard

import android.app.admin.DeviceAdminReceiver

/** Registered as device admin so the app cannot be uninstalled without first deactivating it (which needs Settings = parent PIN). */
class AdminReceiver : DeviceAdminReceiver()
