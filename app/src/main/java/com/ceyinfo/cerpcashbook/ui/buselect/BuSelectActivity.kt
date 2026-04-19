package com.ceyinfo.cerpcashbook.ui.buselect

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.ceyinfo.cerpcashbook.R
import com.ceyinfo.cerpcashbook.data.model.BusinessUnit
import com.ceyinfo.cerpcashbook.data.model.SelectUnitRequest
import com.ceyinfo.cerpcashbook.data.remote.ApiClient
import com.ceyinfo.cerpcashbook.databinding.ActivityBuSelectBinding
import com.ceyinfo.cerpcashbook.ui.hub.ModuleHubActivity
import com.ceyinfo.cerpcashbook.ui.login.LoginActivity
import com.ceyinfo.cerpcashbook.util.SessionManager
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch

class BuSelectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBuSelectBinding
    private lateinit var session: SessionManager
    private lateinit var adapter: BuAdapter

    private var allBusinessUnits: List<BusinessUnit> = emptyList()
    private var treeNodes: List<BuTreeNode> = emptyList()
    private var currentFilter: String? = null
    private var currentSearch: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        binding = ActivityBuSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = SessionManager(this)
        binding.tvUserEmail.text = session.email

        val initial = session.email?.firstOrNull()?.uppercase() ?: "U"
        binding.tvAvatar.text = initial

        binding.rvBusinessUnits.layoutManager = LinearLayoutManager(this)
        binding.btnLogout.setOnClickListener { logout() }
        binding.swipeRefresh.setOnRefreshListener { loadBusinessUnits() }
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)

        setupSearch()
        loadBusinessUnits()
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentSearch = s?.toString()?.trim() ?: ""
                applyFilters()
            }
        })
    }

    private fun setupLevelChips(bus: List<BusinessUnit>) {
        binding.chipGroupLevel.removeAllViews()

        val levelCounts = bus.groupBy { it.level?.lowercase() ?: "unknown" }
            .mapValues { it.value.size }
            .toSortedMap(compareBy {
                when (it) {
                    "organization" -> 0; "division" -> 1; "project" -> 2; "site" -> 3; else -> 4
                }
            })

        val chipItems = mutableListOf<Pair<String?, String>>()
        chipItems.add(null to "All")
        for ((level, count) in levelCounts) {
            val displayName = level.replaceFirstChar { it.uppercase() }
            chipItems.add(level to "$displayName ($count)")
        }

        for ((level, label) in chipItems) {
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                isChecked = level == null
                chipCornerRadius = 20f * resources.displayMetrics.density
                chipStrokeWidth = 1f * resources.displayMetrics.density
                chipStrokeColor = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.divider)
                )
                chipBackgroundColor = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(
                        ContextCompat.getColor(context, R.color.primary),
                        ContextCompat.getColor(context, R.color.white)
                    )
                )
                setTextColor(android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(
                        ContextCompat.getColor(context, R.color.white),
                        ContextCompat.getColor(context, R.color.on_surface)
                    )
                ))
                isCheckedIconVisible = false
                setOnClickListener {
                    currentFilter = level
                    applyFilters()
                }
            }
            binding.chipGroupLevel.addView(chip)
        }
    }

    private fun applyFilters() {
        val isSearching = currentSearch.isNotEmpty() || currentFilter != null

        if (isSearching) {
            var filtered = allBusinessUnits

            if (currentFilter != null) {
                filtered = filtered.filter { it.level?.lowercase() == currentFilter }
            }
            if (currentSearch.isNotEmpty()) {
                val q = currentSearch.lowercase()
                filtered = filtered.filter {
                    it.name.lowercase().contains(q) ||
                    (it.code?.lowercase()?.contains(q) == true)
                }
            }

            filtered = filtered.sortedWith(compareBy({ it.levelOrder ?: 99 }, { it.name }))

            val flatNodes = filtered.map { BuTreeNode(it, 0, false, false) }
            treeNodes = flatNodes
            rebuildAdapter()

            binding.tvCount.text = "${filtered.size} of ${allBusinessUnits.size} units"
        } else {
            treeNodes = BuTreeBuilder.buildTree(allBusinessUnits)
            rebuildAdapter()

            binding.tvCount.text = "${allBusinessUnits.size} business units"
        }

        binding.layoutEmpty.visibility =
            if (treeNodes.isEmpty() && allBusinessUnits.isNotEmpty()) View.VISIBLE else View.GONE
        binding.rvBusinessUnits.visibility =
            if (treeNodes.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun rebuildAdapter() {
        adapter = BuAdapter(
            allBus = allBusinessUnits,
            onSelect = { bu -> selectUnit(bu) },
            onToggle = { position -> toggleTreeNode(position) }
        )
        binding.rvBusinessUnits.adapter = adapter
        adapter.submitNodes(treeNodes)
    }

    private fun toggleTreeNode(position: Int) {
        treeNodes = BuTreeBuilder.toggleNode(treeNodes, position, allBusinessUnits)
        adapter.submitNodes(treeNodes)
    }

    private fun loadBusinessUnits() {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@BuSelectActivity)
                val response = api.getMyRole()
                if (response.isSuccessful && response.body()?.success == true) {
                    val roleData = response.body()!!.data!!

                    // Save role to session
                    session.cashRole = roleData.role

                    // Merge clerk + custodian sites, track role per site
                    val siteMap = mutableMapOf<String, BusinessUnit>()

                    for (site in roleData.clerkSites) {
                        siteMap[site.siteBuId] = BusinessUnit(
                            id = site.siteBuId, name = site.siteName,
                            code = site.code, level = site.level ?: "site",
                            cashRole = "clerk"
                        )
                    }
                    for (site in roleData.custodianSites) {
                        val existing = siteMap[site.siteBuId]
                        if (existing != null) {
                            // Same site, both roles
                            siteMap[site.siteBuId] = existing.copy(cashRole = "both")
                        } else {
                            siteMap[site.siteBuId] = BusinessUnit(
                                id = site.siteBuId, name = site.siteName,
                                code = site.code, level = site.level ?: "site",
                                cashRole = "custodian"
                            )
                        }
                    }

                    val data = siteMap.values.sortedBy { it.name }
                    allBusinessUnits = data
                    setupLevelChips(data)
                    applyFilters()
                }
            } catch (e: Exception) {
                Toast.makeText(this@BuSelectActivity, e.message, Toast.LENGTH_SHORT).show()
            } finally {
                binding.progress.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun selectUnit(bu: BusinessUnit) {
        binding.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@BuSelectActivity)
                val response = api.selectUnit(SelectUnitRequest(bu.id))

                if (response.isSuccessful && response.body()?.success == true) {
                    val data = response.body()!!.data!!
                    session.businessUnitId = data.businessUnitId
                    session.businessUnitName = data.businessUnitName
                    session.cashRole = bu.cashRole ?: session.cashRole

                    // Return to existing Hub instance (don't stack a new one).
                    startActivity(Intent(this@BuSelectActivity, ModuleHubActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                } else {
                    Toast.makeText(
                        this@BuSelectActivity,
                        response.body()?.message ?: "Failed to select unit",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@BuSelectActivity, e.message, Toast.LENGTH_SHORT).show()
            } finally {
                binding.progress.visibility = View.GONE
            }
        }
    }

    private fun logout() {
        lifecycleScope.launch {
            try {
                ApiClient.getService(this@BuSelectActivity).logout()
            } catch (_: Exception) { }
            ApiClient.clearSession()
            session.clearSession()
            startActivity(Intent(this@BuSelectActivity, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
        }
    }
}
