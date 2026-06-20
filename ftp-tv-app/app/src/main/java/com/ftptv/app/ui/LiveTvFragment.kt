package com.ftptv.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ftptv.app.PlayerActivity
import com.ftptv.app.Preferences
import com.ftptv.app.R
import com.ftptv.app.fetcher.ChannelFetcher
import com.ftptv.app.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.URL

class LiveTvFragment : Fragment() {

    private var allChannels: List<Channel> = emptyList()
    private var activeCategory: String = "ALL"
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var prefs: Preferences
    private lateinit var emptyText: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_live_tv, container, false)
        prefs = Preferences(requireContext())

        emptyText = view.findViewById(R.id.emptyText)
        val channelGrid = view.findViewById<RecyclerView>(R.id.channelGrid)
        val categoryList = view.findViewById<RecyclerView>(R.id.categoryList)

        channelAdapter = ChannelAdapter(
            prefs = prefs,
            scope = viewLifecycleOwner.lifecycleScope,
            onClick = { channel ->
                val current = channelAdapter.currentChannels
                val idx = current.indexOf(channel)
                if (idx >= 0) {
                    startActivity(PlayerActivity.newIntent(
                        requireContext(),
                        current.map { it.streamUrl }.toTypedArray(),
                        current.map { it.name }.toTypedArray(),
                        idx
                    ))
                }
            }
        )
        channelAdapter.categoryRowId = R.id.categoryList
        channelGrid.layoutManager = GridLayoutManager(context, 3)
        channelGrid.adapter = channelAdapter

        categoryAdapter = CategoryAdapter { category ->
            activeCategory = category
            filterChannels()
        }
        categoryList.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        categoryList.adapter = categoryAdapter

        emptyText.visibility = View.VISIBLE
        emptyText.text = getString(R.string.loading)

        fetchChannels()

        return view
    }

    override fun onResume() {
        super.onResume()
        if (::emptyText.isInitialized && allChannels.isNotEmpty()) {
            fetchChannels()
        }
    }

    private fun fetchChannels() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ChannelFetcher(prefs.server, prefs.port).fetch()
            }
            result.onSuccess { channels ->
                allChannels = channels
                emptyText.visibility = View.GONE
                val hasFavs = prefs.getFavorites().any { url ->
                    channels.any { it.streamUrl == url }
                }
                val categories = buildList {
                    if (hasFavs) add("FAVORITES")
                    add("ALL")
                    addAll(channels.map { it.category }.distinct())
                }
                categoryAdapter.submitList(categories)
                filterChannels()
            }.onFailure { e ->
                if (allChannels.isEmpty()) {
                    emptyText.visibility = View.VISIBLE
                    emptyText.text = "Failed: ${e.message}"
                }
            }
        }
    }

    private fun filterChannels() {
        val filtered = when (activeCategory) {
            "ALL" -> allChannels
            "FAVORITES" -> {
                val favs = prefs.getFavorites()
                allChannels.filter { it.streamUrl in favs }
            }
            else -> allChannels.filter { it.category == activeCategory }
        }
        channelAdapter.submitList(filtered)
    }
}

class ChannelAdapter(
    private val prefs: Preferences,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

    var currentChannels: List<Channel> = emptyList()
        private set
    var categoryRowId: Int = View.NO_ID

    private val thumbCache = object : LruCache<String, Bitmap>(5 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private val placeholderGradient = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0xFF1A1A2E.toInt(), 0xFF2A2A4E.toInt())
    ).apply { cornerRadius = 4f }

    fun submitList(list: List<Channel>) {
        currentChannels = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        view.isFocusable = true
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = currentChannels[position]
        holder.nameText.text = channel.name

        holder.thumbnailJob?.cancel()

        if (categoryRowId != View.NO_ID && position < 3) {
            holder.itemView.nextFocusUpId = categoryRowId
        }

        holder.itemView.contentDescription = "${channel.name} channel"
        holder.thumbView.contentDescription = "${channel.name} thumbnail"
        holder.itemView.setOnClickListener { onClick(channel) }
        holder.itemView.setOnLongClickListener {
            val nowFav = prefs.toggleFavorite(channel.streamUrl)
            val resId = if (nowFav) R.string.channel_pinned else R.string.channel_unpinned
            Toast.makeText(holder.itemView.context, resId, Toast.LENGTH_SHORT).show()
            true
        }

        val cached = thumbCache[channel.thumbnailUrl]
        if (cached != null) {
            holder.thumbView.setImageBitmap(cached)
        } else {
            holder.thumbView.setImageDrawable(placeholderGradient)
            holder.thumbnailJob = scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    var conn: HttpURLConnection? = null
                    try {
                        val url = URL(channel.thumbnailUrl)
                        conn = url.openConnection() as HttpURLConnection
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        conn.instanceFollowRedirects = true
                        val stream = withTimeout(10_000) { conn.inputStream }
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = 2
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        BitmapFactory.decodeStream(stream, null, opts)
                    } catch (_: Exception) { null } finally {
                        conn?.disconnect()
                    }
                }
                if (bitmap != null && holder.thumbnailJob?.isActive == true) {
                    thumbCache.put(channel.thumbnailUrl, bitmap)
                    holder.thumbView.setImageBitmap(bitmap)
                }
            }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.thumbnailJob?.cancel()
        holder.thumbnailJob = null
    }

    override fun getItemCount() = currentChannels.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.channelName)
        val thumbView: ImageView = view.findViewById(R.id.channelThumb)
        var thumbnailJob: Job? = null
    }
}

class CategoryAdapter(
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {

    private var categories: List<String> = emptyList()
    private var selectedPosition = 0

    fun submitList(list: List<String>) {
        categories = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
        view.isFocusable = true
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val category = categories[position]
        holder.btn.text = category
        holder.btn.isActivated = position == selectedPosition
        holder.btn.contentDescription = "$category category"
        holder.btn.setOnClickListener {
            val prev = selectedPosition
            selectedPosition = position
            notifyItemChanged(prev)
            notifyItemChanged(position)
            onClick(category)
        }
    }

    override fun getItemCount() = categories.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val btn: android.widget.Button = view.findViewById(R.id.categoryBtn)
    }
}
