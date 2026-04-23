package com.ceyinfo.cerpcashbook.ui.common

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ceyinfo.cerpcashbook.R
import com.ceyinfo.cerpcashbook.databinding.SheetConfirmActionBinding
import com.ceyinfo.cerpcashbook.databinding.SheetSuccessBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reusable confirm + success bottom sheets used by every action that
 * mutates server state (Send Cash, Acknowledge, Cancel, Approve, Reject,
 * Submit Expense). Goal: a single-tap → review → confirm → success path
 * with consistent feedback.
 */
object ActionSheets {

    /** A single key/value detail row shown inside the confirm sheet. */
    data class Detail(val label: String, val value: String)

    /** Tone determines the colour of the confirm button. */
    enum class Tone { PRIMARY, DANGER, SUCCESS }

    /**
     * Show a confirm sheet, then run [onConfirm] when the user accepts.
     *
     * The progress bar is shown while [onConfirm] is suspended; the sheet
     * dismisses automatically on completion. If the suspend block throws,
     * the exception is propagated to the caller and the sheet stays open
     * with the buttons re-enabled.
     */
    fun showConfirm(
        context: Context,
        scope: CoroutineScope,
        title: String,
        details: List<Detail>,
        confirmLabel: String,
        warning: String? = null,
        tone: Tone = Tone.PRIMARY,
        onConfirm: suspend () -> Unit,
    ) {
        val binding = SheetConfirmActionBinding.inflate(LayoutInflater.from(context))
        val dialog = BottomSheetDialog(context).apply { setContentView(binding.root) }

        binding.tvTitle.text = title
        binding.btnConfirm.text = confirmLabel

        val toneColor = when (tone) {
            Tone.PRIMARY -> R.color.primary
            Tone.DANGER -> R.color.error
            Tone.SUCCESS -> R.color.success
        }
        binding.btnConfirm.setBackgroundColor(ContextCompat.getColor(context, toneColor))

        // Render detail rows
        val dp = context.resources.displayMetrics.density
        binding.detailsContainer.removeAllViews()
        details.forEachIndexed { idx, d ->
            if (idx > 0) {
                val divider = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
                    ).apply { topMargin = (8 * dp).toInt(); bottomMargin = (8 * dp).toInt() }
                    setBackgroundColor(ContextCompat.getColor(context, R.color.divider))
                }
                binding.detailsContainer.addView(divider)
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(context).apply {
                text = d.label
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(context).apply {
                text = d.value
                setTextColor(ContextCompat.getColor(context, R.color.on_surface))
                textSize = 13f
                paint.isFakeBoldText = true
            })
            binding.detailsContainer.addView(row)
        }

        if (!warning.isNullOrBlank()) {
            binding.tvWarning.visibility = View.VISIBLE
            binding.tvWarning.text = warning
            binding.tvWarning.setTextColor(
                if (tone == Tone.DANGER) Color.parseColor("#DC2626")
                else ContextCompat.getColor(context, R.color.text_secondary)
            )
        }

        binding.btnCancel.setOnClickListener { dialog.dismiss() }
        binding.btnConfirm.setOnClickListener {
            binding.btnCancel.isEnabled = false
            binding.btnConfirm.isEnabled = false
            binding.progress.visibility = View.VISIBLE
            scope.launch {
                try {
                    withContext(Dispatchers.Default) { /* let UI render */ }
                    onConfirm()
                    dialog.dismiss()
                } catch (e: Exception) {
                    binding.progress.visibility = View.GONE
                    binding.btnCancel.isEnabled = true
                    binding.btnConfirm.isEnabled = true
                    binding.tvWarning.visibility = View.VISIBLE
                    binding.tvWarning.text = e.message ?: "Action failed"
                    binding.tvWarning.setTextColor(Color.parseColor("#DC2626"))
                }
            }
        }

        dialog.show()
    }

    /**
     * Show a success sheet with optional primary + secondary CTAs.
     * Pass `null` for [secondaryLabel] to hide the secondary button.
     */
    fun showSuccess(
        context: Context,
        title: String,
        subtitle: String? = null,
        primaryLabel: String = "Done",
        onPrimary: () -> Unit = {},
        secondaryLabel: String? = null,
        onSecondary: () -> Unit = {},
    ) {
        val binding = SheetSuccessBinding.inflate(LayoutInflater.from(context))
        val dialog = BottomSheetDialog(context).apply { setContentView(binding.root) }

        binding.tvTitle.text = title
        if (subtitle.isNullOrBlank()) {
            binding.tvSubtitle.visibility = View.GONE
        } else {
            binding.tvSubtitle.text = subtitle
        }
        binding.btnPrimary.text = primaryLabel
        binding.btnPrimary.setOnClickListener {
            dialog.dismiss(); onPrimary()
        }

        if (secondaryLabel.isNullOrBlank()) {
            binding.btnSecondary.visibility = View.GONE
        } else {
            binding.btnSecondary.visibility = View.VISIBLE
            binding.btnSecondary.text = secondaryLabel
            binding.btnSecondary.setOnClickListener {
                dialog.dismiss(); onSecondary()
            }
        }

        dialog.show()
    }
}
