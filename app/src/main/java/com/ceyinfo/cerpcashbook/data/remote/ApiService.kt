package com.ceyinfo.cerpcashbook.data.remote

import com.ceyinfo.cerpcashbook.data.model.*
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    // ── Auth ──────────────────────────────────────────────────────────
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<ApiResponse<LoginData>>

    @POST("auth/logout")
    suspend fun logout(): Response<ApiResponse<Any>>

    @GET("site/verify-module")
    suspend fun verifyModule(): Response<ApiResponse<VerifyModuleData>>

    @GET("site/permitted-units")
    suspend fun getPermittedUnits(): Response<ApiResponse<List<BusinessUnit>>>

    @POST("site/select-unit")
    suspend fun selectUnit(@Body request: SelectUnitRequest): Response<ApiResponse<SelectUnitData>>

    // ── Site Cash: Role ───────────────────────────────────────────────
    @GET("site-cash/my-role")
    suspend fun getMyRole(): Response<ApiResponse<MyRoleData>>

    // ── Site Cash: Sites & Custodians ─────────────────────────────────
    @GET("site-cash/sites/my-sites")
    suspend fun getMySites(): Response<ApiResponse<List<CashSite>>>

    @GET("site-cash/sites/{siteId}/custodians")
    suspend fun getSiteCustodians(@Path("siteId") siteId: String): Response<ApiResponse<List<Custodian>>>

    // ── Site Cash: Balance ────────────────────────────────────────────
    @GET("site-cash/custodians/{id}/balance")
    suspend fun getCustodianBalance(
        @Path("id") custodianId: String,
        @Query("site_bu_id") siteBuId: String
    ): Response<ApiResponse<CustodianBalance>>

    // ── Site Cash: Advances ───────────────────────────────────────────
    @GET("site-cash/cash-advances")
    suspend fun getCashAdvances(
        @Query("site_bu_id") siteBuId: String? = null,
        @Query("custodian_id") custodianId: String? = null,
        @Query("status") status: String? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20
    ): Response<ApiResponse<List<CashAdvance>>>

    @POST("site-cash/cash-advances")
    suspend fun createCashAdvance(@Body request: CreateAdvanceRequest): Response<ApiResponse<CashAdvance>>

    @PATCH("site-cash/cash-advances/{id}/acknowledge")
    suspend fun acknowledgeAdvance(@Path("id") id: String): Response<ApiResponse<AcknowledgeData>>

    @PATCH("site-cash/cash-advances/{id}/cancel")
    suspend fun cancelAdvance(@Path("id") id: String): Response<ApiResponse<Any>>

    // ── Site Cash: Expense Vouchers ───────────────────────────────────
    @GET("site-cash/expense-vouchers")
    suspend fun getExpenseVouchers(
        @Query("site_bu_id") siteBuId: String? = null,
        @Query("custodian_id") custodianId: String? = null,
        @Query("status") status: String? = null,
        @Query("search") search: String? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20
    ): Response<ApiResponse<List<ExpenseVoucher>>>

    @POST("site-cash/expense-vouchers")
    suspend fun createExpenseVoucher(@Body request: CreateVoucherRequest): Response<ApiResponse<ExpenseVoucher>>

    @PATCH("site-cash/expense-vouchers/{id}/review")
    suspend fun reviewVoucher(
        @Path("id") id: String,
        @Body request: ReviewVoucherRequest
    ): Response<ApiResponse<Any>>

    // ── Site Cash: Ledger ─────────────────────────────────────────────
    @GET("site-cash/ledger")
    suspend fun getLedger(
        @Query("site_bu_id") siteBuId: String? = null,
        @Query("custodian_id") custodianId: String? = null,
        @Query("txn_type") txnType: String? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20
    ): Response<ApiResponse<List<LedgerEntry>>>

    // ── Site Cash: Notifications ──────────────────────────────────────
    @GET("site-cash/notifications")
    suspend fun getNotifications(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20
    ): Response<NotificationResponse>

    @PATCH("site-cash/notifications/{id}/read")
    suspend fun markNotificationRead(@Path("id") id: String): Response<ApiResponse<Any>>

    @PATCH("site-cash/notifications/read-all")
    suspend fun markAllNotificationsRead(): Response<ApiResponse<Any>>

    // ── Site Cash: Dashboard ──────────────────────────────────────────
    @GET("site-cash/dashboard-stats")
    suspend fun getDashboardStats(@Query("site_bu_id") siteBuId: String): Response<ApiResponse<DashboardStats>>
}
