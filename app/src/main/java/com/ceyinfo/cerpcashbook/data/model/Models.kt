package com.ceyinfo.cerpcashbook.data.model

import com.google.gson.annotations.SerializedName

// ── API Response wrapper ──

data class ApiResponse<T>(
    val success: Boolean,
    val message: String? = null,
    val data: T? = null,
    val total: Int? = null,
    val page: Int? = null,
    val limit: Int? = null
)

// ── Auth ──

data class LoginRequest(
    val email: String,
    val password: String,
    val organizationId: String? = null
)

data class LoginData(
    val userId: String,
    val email: String,
    val organizationId: String,
    val isOwner: Boolean,
    val isGypsy: Boolean,
    val permittedBusinessUnits: List<BusinessUnit>,
    val selectOrgRequired: Boolean? = null,
    val organizations: List<Organization>? = null
)

data class Organization(
    val id: String,
    val name: String
)

data class BusinessUnit(
    val id: String,
    val name: String,
    val code: String? = null,
    @SerializedName("parent_id") val parentId: String? = null,
    val level: String? = null,
    @SerializedName("level_order") val levelOrder: Int? = null,
    @SerializedName("is_active") val isActive: Boolean? = null,
    @SerializedName("is_primary") val isPrimary: Boolean = false,
    val cashRole: String? = null  // "clerk" | "custodian" | "both" — local field, not from API
)

data class SelectUnitRequest(
    val businessUnitId: String
)

data class SelectUnitData(
    val businessUnitId: String,
    val businessUnitName: String,
    val roleLabel: String? = null,
    val roleName: String? = null,
    val isOwner: Boolean
)

data class VerifyModuleData(
    val userId: String,
    val email: String,
    val organizationId: String,
    val organizationName: String,
    val employeeName: String? = null,
    val roleLabel: String? = null,
    val isOwner: Boolean,
    val isGypsy: Boolean,
    val businessUnitId: String? = null,
    val businessUnitName: String? = null,
    val businessUnitLevel: String? = null
)

// ── Site Cash: Role ──

data class MyRoleData(
    val role: String,
    @SerializedName("is_owner") val isOwner: Boolean? = null,
    @SerializedName("clerk_sites") val clerkSites: List<CashSite>,
    @SerializedName("custodian_sites") val custodianSites: List<CashSite>
)

data class CashSite(
    @SerializedName("bu_id") val buId: String,
    @SerializedName("bu_name") val buName: String,
    val code: String? = null,
    val level: String? = null
)

// ── Site Cash: Permissions (merged ACL across all BUs/roles) ──

data class MyPermissionsData(
    @SerializedName("is_owner") val isOwner: Boolean = false,
    val entities: Map<String, EntityPermissions> = emptyMap()
)

data class EntityPermissions(
    val allowed: Boolean = false,
    @SerializedName("entity_blocked") val entityBlocked: Boolean = false,
    @SerializedName("allowed_actions") val allowedActions: List<String> = emptyList(),
    val permissions: List<AclPermissionRow> = emptyList(),
    val transitions: List<AclTransitionRow> = emptyList()
)

data class AclPermissionRow(
    @SerializedName("state_id") val stateId: String? = null,
    @SerializedName("state_code") val stateCode: String? = null,
    @SerializedName("access_type") val accessType: String,
    @SerializedName("target_code") val targetCode: String,
    val permission: String
)

data class AclTransitionRow(
    @SerializedName("from_state_code") val fromStateCode: String? = null,
    @SerializedName("to_state_code") val toStateCode: String,
    @SerializedName("transition_code") val transitionCode: String,
    @SerializedName("transition_name") val transitionName: String,
    @SerializedName("action_icon") val actionIcon: String? = null
)

// ── Site Cash: Custodians ──

data class Custodian(
    @SerializedName("user_id") val userId: String,
    @SerializedName("first_name") val firstName: String,
    @SerializedName("last_name") val lastName: String,
    val email: String? = null,
    val balance: Double = 0.0,
    @SerializedName("last_txn_at") val lastTxnAt: String? = null
)

/**
 * One row per (custodian, site) pair the current clerk can disburse to.
 * Returned by `GET /site-cash/custodians/reachable` — a custodian assigned
 * to multiple sites appears multiple times, each row carrying its own
 * site context.
 */
data class ReachableCustodian(
    @SerializedName("user_id") val userId: String,
    @SerializedName("first_name") val firstName: String,
    @SerializedName("last_name") val lastName: String,
    val email: String? = null,
    @SerializedName("bu_id") val buId: String,
    @SerializedName("bu_name") val buName: String,
    @SerializedName("bu_level") val buLevel: String? = null,
    val balance: Double = 0.0,
    @SerializedName("last_txn_at") val lastTxnAt: String? = null
)

// ── Site Cash: Balance ──

data class CustodianBalance(
    @SerializedName("total_received") val totalReceived: Double,
    @SerializedName("total_spent") val totalSpent: Double,
    val balance: Double,
    @SerializedName("recent_transactions") val recentTransactions: List<LedgerEntry>
)

// ── Site Cash: Advances ──

data class CashAdvance(
    val id: String,
    @SerializedName("bu_id") val buId: String,
    @SerializedName("recipient_id") val recipientId: String,
    @SerializedName("sender_id") val senderId: String,
    val amount: Double,
    val currency: String = "LKR",
    @SerializedName("reference_no") val referenceNo: String? = null,
    val description: String? = null,
    val status: String,
    @SerializedName("acknowledged_at") val acknowledgedAt: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("recipient_first_name") val recipientFirstName: String? = null,
    @SerializedName("recipient_last_name") val recipientLastName: String? = null,
    @SerializedName("sender_first_name") val senderFirstName: String? = null,
    @SerializedName("sender_last_name") val senderLastName: String? = null,
    @SerializedName("bu_name") val buName: String? = null,
    @SerializedName("method_of_transfer") val methodOfTransfer: String? = null,
    @SerializedName("bank_account_id") val bankAccountId: String? = null,
    @SerializedName("bank_account_name") val bankAccountName: String? = null,
    @SerializedName("bank_account_number") val bankAccountNumber: String? = null,
    @SerializedName("bank_name") val bankName: String? = null,
    @SerializedName("receipt_url") val receiptUrl: String? = null
)

data class CreateAdvanceRequest(
    @SerializedName("bu_id") val buId: String,
    @SerializedName("recipient_id") val recipientId: String,
    val amount: Double,
    val currency: String = "LKR",
    @SerializedName("reference_no") val referenceNo: String? = null,
    val description: String? = null,
    @SerializedName("method_of_transfer") val methodOfTransfer: String? = null,
    @SerializedName("bank_account_id") val bankAccountId: String? = null,
    @SerializedName("receipt_url") val receiptUrl: String? = null,
    @SerializedName("advance_date") val advanceDate: String? = null
)

// ── Site Cash: Bank Accounts ──

data class BankAccount(
    val id: String,
    @SerializedName("bu_id") val buId: String,
    @SerializedName("account_name") val accountName: String,
    @SerializedName("account_number") val accountNumber: String,
    @SerializedName("account_holder_name") val accountHolderName: String? = null,
    @SerializedName("bank_name") val bankName: String? = null,
    val branch: String? = null,
    @SerializedName("branch_code") val branchCode: String? = null,
    val currency: String = "LKR",
    @SerializedName("is_active") val isActive: Boolean = true,
    @SerializedName("created_at") val createdAt: String? = null
)

data class CreateBankAccountRequest(
    @SerializedName("bu_id") val buId: String,
    @SerializedName("account_name") val accountName: String,
    @SerializedName("account_number") val accountNumber: String,
    @SerializedName("account_holder_name") val accountHolderName: String? = null,
    @SerializedName("bank_name") val bankName: String? = null,
    val branch: String? = null,
    @SerializedName("branch_code") val branchCode: String? = null,
    val currency: String = "LKR"
)

data class UploadReceiptData(
    val url: String,
    @SerializedName("file_key") val fileKey: String? = null
)

data class AcknowledgeData(
    val balance: Double
)

// ── Site Cash: Expense Vouchers ──

data class ExpenseVoucher(
    val id: String,
    @SerializedName("voucher_no") val voucherNo: String,
    @SerializedName("bu_id") val buId: String,
    @SerializedName("recipient_id") val recipientId: String,
    @SerializedName("expense_date") val expenseDate: String,
    val category: String? = null,
    val description: String,
    val amount: Double,
    val currency: String = "LKR",
    @SerializedName("receipt_url") val receiptUrl: String? = null,
    val status: String,
    @SerializedName("reviewed_by") val reviewedBy: String? = null,
    @SerializedName("reviewed_at") val reviewedAt: String? = null,
    @SerializedName("rejection_reason") val rejectionReason: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("recipient_first_name") val recipientFirstName: String? = null,
    @SerializedName("recipient_last_name") val recipientLastName: String? = null,
    @SerializedName("bu_name") val buName: String? = null
)

data class CreateVoucherRequest(
    @SerializedName("bu_id") val buId: String,
    @SerializedName("expense_date") val expenseDate: String,
    val category: String? = null,
    val description: String,
    val amount: Double,
    val currency: String = "LKR",
    @SerializedName("receipt_url") val receiptUrl: String? = null
)

data class ReviewVoucherRequest(
    val action: String,  // "approve" or "reject"
    @SerializedName("rejection_reason") val rejectionReason: String? = null
)

// ── Site Cash: Ledger ──

data class LedgerEntry(
    val id: String,
    @SerializedName("txn_date") val txnDate: String,
    @SerializedName("txn_type") val txnType: String,
    val debit: Double = 0.0,
    val credit: Double = 0.0,
    @SerializedName("running_balance") val runningBalance: Double,
    val description: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("recipient_first_name") val recipientFirstName: String? = null,
    @SerializedName("recipient_last_name") val recipientLastName: String? = null,
    @SerializedName("bu_name") val buName: String? = null
)

// ── Site Cash: Notifications ──

data class CashNotification(
    val id: String,
    @SerializedName("recipient_id") val recipientId: String,
    @SerializedName("sender_id") val senderId: String? = null,
    @SerializedName("bu_id") val buId: String,
    val type: String,
    @SerializedName("reference_id") val referenceId: String,
    @SerializedName("reference_type") val referenceType: String,
    val title: String,
    val message: String? = null,
    @SerializedName("is_read") val isRead: Boolean = false,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("sender_first_name") val senderFirstName: String? = null,
    @SerializedName("sender_last_name") val senderLastName: String? = null,
    @SerializedName("bu_name") val buName: String? = null
)

data class NotificationResponse(
    val success: Boolean,
    val data: List<CashNotification>? = null,
    val total: Int? = null,
    val page: Int? = null,
    val limit: Int? = null,
    val unread: Int? = null
)

// ── Site Cash: Dashboard ──

data class DashboardStats(
    val advances: AdvanceStats,
    val vouchers: VoucherStats,
    @SerializedName("unread_notifications") val unreadNotifications: Int = 0
)

data class AdvanceStats(
    @SerializedName("total_advances") val totalAdvances: Int = 0,
    @SerializedName("pending_advances") val pendingAdvances: Int = 0,
    @SerializedName("acknowledged_advances") val acknowledgedAdvances: Int = 0,
    @SerializedName("total_disbursed") val totalDisbursed: Double = 0.0
)

data class VoucherStats(
    @SerializedName("total_vouchers") val totalVouchers: Int = 0,
    @SerializedName("pending_review") val pendingReview: Int = 0,
    @SerializedName("approved_vouchers") val approvedVouchers: Int = 0,
    @SerializedName("rejected_vouchers") val rejectedVouchers: Int = 0,
    @SerializedName("total_expenses") val totalExpenses: Double = 0.0
)
