package com.ceyinfo.cerpcashbook.ui.buselect

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.RotateAnimation
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.ceyinfo.cerpcashbook.R
import com.ceyinfo.cerpcashbook.data.model.BusinessUnit
import com.ceyinfo.cerpcashbook.databinding.ItemBusinessUnitBinding

class BuAdapter(
    private val allBus: List<BusinessUnit>,
    private val onSelect: (BusinessUnit) -> Unit,
    private val onToggle: (Int) -> Unit
) : RecyclerView.Adapter<BuAdapter.ViewHolder>() {

    private var nodes: List<BuTreeNode> = emptyList()

    fun submitNodes(newNodes: List<BuTreeNode>) {
        nodes = newNodes
        notifyDataSetChanged()
    }

    override fun getItemCount() = nodes.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBusinessUnitBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(nodes[position])
    }

    inner class ViewHolder(
        private val binding: ItemBusinessUnitBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(node: BuTreeNode) {
            val ctx = binding.root.context
            val bu = node.bu
            val dp = ctx.resources.displayMetrics.density

            val indentPx = (node.depth * 24 * dp).toInt()
            binding.spacerIndent.layoutParams = binding.spacerIndent.layoutParams.apply {
                width = indentPx
            }

            binding.tvBuName.text = bu.name
            binding.tvBuName.paint.isFakeBoldText = node.hasChildren

            // Role badge (Clerk / Custodian / Both)
            val roleText = when (bu.cashRole) {
                "clerk" -> "CLERK"
                "custodian" -> "CUSTODIAN"
                "both" -> "CLERK + CUSTODIAN"
                else -> bu.level?.uppercase() ?: "SITE"
            }
            val roleColor = when (bu.cashRole) {
                "clerk" -> R.color.info
                "custodian" -> R.color.warning
                "both" -> R.color.success
                else -> R.color.primary
            }
            binding.tvBuLevel.text = roleText
            applyColor(binding.tvBuLevel.background, roleColor, ctx, 6f * dp)
            applyColor(binding.barLevel.background, roleColor, ctx, 2f * dp)

            // No tree hierarchy — flat site list
            binding.ivExpand.visibility = View.GONE
            binding.dotLeaf.visibility = View.VISIBLE
            binding.tvChildCount.visibility = View.GONE
            applyColor(binding.dotLeaf.background, roleColor, ctx, 4f * dp)

            // Show code if available
            if (!bu.code.isNullOrEmpty()) {
                binding.tvBuCode.text = bu.code
                binding.tvBuCode.visibility = View.VISIBLE
            } else {
                binding.tvBuCode.visibility = View.GONE
            }

            // All sites are selectable
            binding.ivSelect.visibility = View.VISIBLE
            applyColor(binding.ivSelect.background, roleColor, ctx, 14f * dp)
            binding.ivSelect.setOnClickListener { onSelect(bu) }

            binding.ivExpand.setOnClickListener {
                animateArrow(binding.ivExpand, node.isExpanded)
                onToggle(adapterPosition)
            }

            binding.cardRoot.setOnClickListener {
                onSelect(bu)
            }
        }

        private fun animateArrow(view: View, currentlyExpanded: Boolean) {
            val from = if (currentlyExpanded) -180f else 0f
            val to = if (currentlyExpanded) 0f else -180f
            val anim = RotateAnimation(from, to, view.width / 2f, view.height / 2f).apply {
                duration = 200
                fillAfter = true
            }
            view.startAnimation(anim)
        }

        private fun applyColor(drawable: android.graphics.drawable.Drawable?, colorRes: Int, ctx: android.content.Context, radius: Float) {
            val bg = drawable as? GradientDrawable ?: return
            bg.setColor(ContextCompat.getColor(ctx, colorRes))
            bg.cornerRadius = radius
        }
    }

    companion object {
        fun getLevelColor(level: String?): Int {
            return when (level?.lowercase()) {
                "organization" -> R.color.primary
                "division" -> R.color.info
                "project" -> R.color.success
                "site" -> R.color.warning
                else -> R.color.primary
            }
        }
    }
}
