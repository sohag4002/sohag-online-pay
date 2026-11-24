package com.sohagonline.payment

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.sohagonline.payment.data.SmsRecord
import com.sohagonline.payment.databinding.ItemSmsBinding

class SmsAdapter(private var items: List<SmsRecord>) : RecyclerView.Adapter<SmsAdapter.ViewHolder>() {

    fun updateData(newItems: List<SmsRecord>) {
        this.items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSmsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(private val binding: ItemSmsBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SmsRecord) {
            val context = binding.root.context
            val lowerSender = item.address.lowercase()

            // Provider badge styling
            when {
                lowerSender.contains("bkash") -> {
                    binding.tvProvider.text = "bKash"
                    binding.tvProvider.backgroundTintList = ContextCompat.getColorStateList(context, R.color.bkash_pink)
                }
                lowerSender.contains("nagad") || lowerSender.contains("16167") -> {
                    binding.tvProvider.text = "Nagad"
                    binding.tvProvider.backgroundTintList = ContextCompat.getColorStateList(context, R.color.nagad_orange)
                }
                lowerSender.contains("rocket") || lowerSender.contains("16216") -> {
                    binding.tvProvider.text = "Rocket"
                    binding.tvProvider.backgroundTintList = ContextCompat.getColorStateList(context, R.color.rocket_purple)
                }
                lowerSender.contains("upay") -> {
                    binding.tvProvider.text = "Upay"
                    binding.tvProvider.backgroundTintList = ContextCompat.getColorStateList(context, R.color.upay_navy)
                }
                else -> {
                    binding.tvProvider.text = "Bank/SMS"
                    binding.tvProvider.backgroundTintList = ContextCompat.getColorStateList(context, R.color.primary_navy)
                }
            }

            binding.tvSender.text = item.address
            binding.tvMessage.text = item.message

            // Relative time (e.g. 5 minutes ago)
            val timeSpan = DateUtils.getRelativeTimeSpanString(
                item.timestamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )
            binding.tvTime.text = timeSpan

            // Status Badge
            if (item.status == SmsRecord.STATUS_SUCCESS) {
                binding.tvStatus.text = "✓ সার্ভারে সফল"
                binding.tvStatus.setTextColor(ContextCompat.getColor(context, R.color.status_success))
                binding.tvStatus.backgroundTintList = ContextCompat.getColorStateList(context, R.color.status_success_bg)
            } else {
                binding.tvStatus.text = "⏳ পেন্ডিং (রিট্রাই চলছে)"
                binding.tvStatus.setTextColor(ContextCompat.getColor(context, R.color.status_pending))
                binding.tvStatus.backgroundTintList = ContextCompat.getColorStateList(context, R.color.status_pending_bg)
            }

            binding.tvResponse.text = item.responseMessage.ifBlank { "Synced" }
        }
    }
}
