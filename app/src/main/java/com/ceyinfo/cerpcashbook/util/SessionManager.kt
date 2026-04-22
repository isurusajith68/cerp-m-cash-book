package com.ceyinfo.cerpcashbook.util

import android.content.Context
import android.content.SharedPreferences
import com.ceyinfo.cerpcashbook.data.model.CashSite
import com.ceyinfo.cerpcashbook.data.model.EntityPermissions
import com.ceyinfo.cerpcashbook.data.model.MyPermissionsData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class SessionManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("cashbook_session", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EMAIL = "email"
        private const val KEY_ORG_ID = "organization_id"
        private const val KEY_IS_OWNER = "is_owner"
        private const val KEY_BU_ID = "business_unit_id"
        private const val KEY_BU_NAME = "business_unit_name"
        private const val KEY_BU_LEVEL = "business_unit_level"
        private const val KEY_CASH_ROLE = "cash_role"
        private const val KEY_CLERK_SITES = "clerk_sites"
        private const val KEY_CUSTODIAN_SITES = "custodian_sites"
        private const val KEY_PERMISSIONS = "my_permissions"
    }

    var isLoggedIn: Boolean
        get() = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_LOGGED_IN, value).apply()

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var email: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_EMAIL, value).apply()

    var organizationId: String?
        get() = prefs.getString(KEY_ORG_ID, null)
        set(value) = prefs.edit().putString(KEY_ORG_ID, value).apply()

    var isOwner: Boolean
        get() = prefs.getBoolean(KEY_IS_OWNER, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_OWNER, value).apply()

    var businessUnitId: String?
        get() = prefs.getString(KEY_BU_ID, null)
        set(value) = prefs.edit().putString(KEY_BU_ID, value).apply()

    var businessUnitName: String?
        get() = prefs.getString(KEY_BU_NAME, null)
        set(value) = prefs.edit().putString(KEY_BU_NAME, value).apply()

    var businessUnitLevel: String?
        get() = prefs.getString(KEY_BU_LEVEL, null)
        set(value) = prefs.edit().putString(KEY_BU_LEVEL, value).apply()

    // Cash-book specific
    var cashRole: String?
        get() = prefs.getString(KEY_CASH_ROLE, null)
        set(value) = prefs.edit().putString(KEY_CASH_ROLE, value).apply()

    fun saveClerkSites(sites: List<CashSite>) {
        prefs.edit().putString(KEY_CLERK_SITES, gson.toJson(sites)).apply()
    }

    fun getClerkSites(): List<CashSite> {
        val json = prefs.getString(KEY_CLERK_SITES, null) ?: return emptyList()
        val type = object : TypeToken<List<CashSite>>() {}.type
        return gson.fromJson(json, type)
    }

    fun saveCustodianSites(sites: List<CashSite>) {
        prefs.edit().putString(KEY_CUSTODIAN_SITES, gson.toJson(sites)).apply()
    }

    fun getCustodianSites(): List<CashSite> {
        val json = prefs.getString(KEY_CUSTODIAN_SITES, null) ?: return emptyList()
        val type = object : TypeToken<List<CashSite>>() {}.type
        return gson.fromJson(json, type)
    }

    // ── Merged ACL permissions across all BUs/roles ──

    fun savePermissions(perms: MyPermissionsData?) {
        if (perms == null) prefs.edit().remove(KEY_PERMISSIONS).apply()
        else prefs.edit().putString(KEY_PERMISSIONS, gson.toJson(perms)).apply()
    }

    fun getPermissions(): MyPermissionsData? {
        val json = prefs.getString(KEY_PERMISSIONS, null) ?: return null
        return runCatching { gson.fromJson(json, MyPermissionsData::class.java) }.getOrNull()
    }

    /** Convenience: per-entity gating without forcing every caller to null-check the map. */
    fun entityPermissions(entityCode: String): EntityPermissions? =
        getPermissions()?.entities?.get(entityCode)

    /** True when the user can reach this entity at all (owner or unblocked role). */
    fun isEntityAllowed(entityCode: String): Boolean {
        val perms = getPermissions() ?: return false
        if (perms.isOwner) return true
        return perms.entities[entityCode]?.allowed == true
    }

    /** True when a specific action target_code is in the user's allowed list. */
    fun canPerformAction(entityCode: String, actionCode: String): Boolean {
        val perms = getPermissions() ?: return false
        if (perms.isOwner) return true
        val ent = perms.entities[entityCode] ?: return false
        return ent.allowed && ent.allowedActions.contains(actionCode)
    }

    fun clearSession() {
        prefs.edit().clear().apply()
    }
}
