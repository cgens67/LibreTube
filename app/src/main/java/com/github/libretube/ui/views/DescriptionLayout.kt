package com.github.libretube.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.text.util.Linkify
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.text.method.LinkMovementMethodCompat
import androidx.core.text.parseAsHtml
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.github.libretube.R
import com.github.libretube.api.SponsorBlockLabelHelper
import com.github.libretube.api.obj.Segment
import com.github.libretube.api.obj.Streams
import com.github.libretube.databinding.DescriptionLayoutBinding
import com.github.libretube.enums.SbSkipOptions
import com.github.libretube.extensions.formatShort
import com.github.libretube.helpers.ClipboardHelper
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.ui.activities.VideoTagsAdapter
import com.github.libretube.ui.extensions.setupSubscriptionButton
import com.github.libretube.util.HtmlParser
import com.github.libretube.util.LinkHandler
import com.github.libretube.util.TextUtils
import java.util.Locale

class DescriptionLayout(
    context: Context,
    attributeSet: AttributeSet? = null
) : LinearLayout(context, attributeSet) {

    val binding = DescriptionLayoutBinding.inflate(LayoutInflater.from(context), this, true)
    private var streams: Streams? = null
    var handleLink: (link: String) -> Unit = {}

    // YouTube-style callbacks
    var onCommentsClick: () -> Unit = {}
    var onShareClick: () -> Unit = {}
    var onDownloadClick: () -> Unit = {}
    var onMoreOptionsClick: () -> Unit = {}
    var onRemixClick: () -> Unit = {}
    var onLikeClick: () -> Unit = {}
    var onDislikeClick: () -> Unit = {}

    private val videoTagsAdapter = VideoTagsAdapter()

    init {
        orientation = VERTICAL

        binding.playerTitle.setOnClickListener {
            toggleDescription()
        }
        binding.metadataSubtext.setOnClickListener {
            toggleDescription()
        }

        binding.playerTitle.setOnLongClickListener {
            streams?.title?.let { ClipboardHelper.save(context, text = it) }
            true
        }

        binding.tagsRecycler.adapter = videoTagsAdapter

        // Action pills click listeners
        binding.btnShare.setOnClickListener { onShareClick() }
        binding.btnMoreOptions.setOnClickListener { onMoreOptionsClick() }
        binding.btnRemix.setOnClickListener { onRemixClick() }
        binding.btnLikeIcon.setOnClickListener { onLikeClick() }
        binding.btnDislikeIcon.setOnClickListener { onDislikeClick() }

        // Comments preview card
        binding.commentsCard.setOnClickListener { onCommentsClick() }
    }

    fun setSegments(segments: List<Segment>) {
        if (PlayerHelper.getSponsorBlockCategories()[SB_SPONSOR_CATEGORY] == SbSkipOptions.OFF) {
            return
        }

        val category = segments.filter { it.actionType == Segment.TYPE_FULL }.firstNotNullOfOrNull { it.category }
        binding.playerSponsorBadge.isVisible = category != null
        binding.playerSponsorBadge.chipIcon = SponsorBlockLabelHelper.categoryIcon(category)?.let { context.getDrawable(it) }
        binding.playerSponsorBadge.text = SponsorBlockLabelHelper.categoryLabel(category)?.let { context.getString(it) }
    }

    @SuppressLint("SetTextI18n")
    fun setStreams(streams: Streams) {
        this.streams = streams

        val views = streams.views.formatShort()
        val date = TextUtils.formatRelativeDate(streams.uploaded ?: -1L)
        val likes = streams.likes.formatShort()

        binding.run {
            // 1. Title (max 2 lines in collapsed state)
            playerTitle.text = streams.title

            // 2. Metadata Subtext: @handle • likes • views • time • more
            val handleOrName = streams.uploader
            metadataSubtext.text = "$handleOrName  •  $likes likes  •  $views views  •  $date  more"

            // 3. Channel Info Row & Subscribe Pill
            channelName.text = streams.uploader
            ImageHelper.loadImage(streams.uploaderAvatar, channelAvatar, true)

            channelAvatar.setOnClickListener {
                NavigationHelper.navigateChannel(context, streams.uploaderUrl)
            }
            channelName.setOnClickListener {
                NavigationHelper.navigateChannel(context, streams.uploaderUrl)
            }

            btnSubscribe.setupSubscriptionButton(
                streams.uploaderUrl?.substringAfterLast("/"),
                streams.uploader,
                streams.uploaderAvatar,
                streams.uploaderVerified
            )

            // 4. Action Pill Bar
            likeCountText.text = likes

            // 5. Comments Preview Teaser Card
            val commentCount = streams.commentsCount ?: 0
            commentsTitle.text = "${context.getString(R.string.comments)}  $commentCount"

            if (!streams.previewComment.isNullOrBlank()) {
                commentTeaserAvatar.isVisible = true
                commentTeaserText.text = streams.previewComment
                ImageHelper.loadImage(streams.previewCommentAvatar, commentTeaserAvatar, true)
            } else {
                commentTeaserAvatar.isGone = true
                commentTeaserText.text = context.getString(R.string.comments)
            }

            // Description and Meta Info
            metaInfo.isVisible = streams.metaInfo.isNotEmpty()
            val metaInfoText = streams.metaInfo.joinToString("\n\n") { info ->
                val text = info.description.takeIf { it.isNotBlank() } ?: info.title
                val links = info.urls.mapIndexed { index, url ->
                    "<a href=\"$url\">${info.urlTexts.getOrNull(index).orEmpty()}</a>"
                }.joinToString(", ")
                "$text $links"
            }
            metaInfo.text = metaInfoText.parseAsHtml()

            val visibility = when (streams.visibility) {
                "public" -> context?.getString(R.string.visibility_public)
                "unlisted" -> context?.getString(R.string.visibility_unlisted)
                else -> streams.visibility.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                }
            }.orEmpty()
            additionalVideoInfo.text =
                "${context?.getString(R.string.category)}: ${streams.category}\n" +
                "${context?.getString(R.string.license)}: ${streams.license}\n" +
                "${context?.getString(R.string.visibility)}: $visibility"

            if (streams.tags.isNotEmpty()) {
                videoTagsAdapter.submitList(streams.tags)
            }
            tagsRecycler.isVisible = streams.tags.isNotEmpty()

            setupDescription(streams.description)
        }
    }

    fun collapseDescription() {
        if (binding.playerDescription.isVisible) {
            toggleDescription()
        }
    }

    private fun setupDescription(description: String) {
        val descTextView = binding.playerDescription
        if (description.contains("<") && description.contains(">")) {
            descTextView.movementMethod = LinkMovementMethodCompat.getInstance()
            descTextView.text = description.replace("</a>", "</a> ")
                .parseAsHtml(tagHandler = HtmlParser(LinkHandler(handleLink)))
        } else {
            descTextView.autoLinkMask = Linkify.WEB_URLS
            descTextView.text = description
        }
    }

    private fun toggleDescription() {
        val isNewStateExpanded = !binding.playerDescription.isVisible
        binding.playerDescription.isVisible = isNewStateExpanded
        binding.descLinLayout.isVisible = isNewStateExpanded
        binding.playerTitle.maxLines = if (isNewStateExpanded) Int.MAX_VALUE else 2
    }

    companion object {
        private const val SB_SPONSOR_CATEGORY = "sponsor_category"
    }
}
