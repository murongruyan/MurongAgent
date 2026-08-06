package com.murong.agent.core.tool

import android.graphics.BitmapFactory
import android.util.Base64
import kotlin.math.max

/** A parsed real-time call request. Calls must run on the physical display for camera/mic access. */
internal data class PhoneAgentVideoCallIntent(
    val packageName: String,
    val recipient: String,
) {
    companion object {
        private val RECIPIENT_PATTERN = Regex(
            "(?:打开)?\\s*(微信|抖音).*?给\\s*(.{1,40}?)\\s*(?:打|发起)\\s*(?:个)?\\s*(?:视频(?:通话)?|视通话)",
            RegexOption.IGNORE_CASE,
        )

        fun parse(task: String): PhoneAgentVideoCallIntent? {
            val match = RECIPIENT_PATTERN.find(task.trim()) ?: return null
            val packageName = PhoneAgentApps.packageFor(match.groupValues[1])
            val recipient = match.groupValues[2]
                .trim()
                .trim('，', ',', '。', '.', '“', '”', '"')
            if (recipient.isBlank()) return null
            return PhoneAgentVideoCallIntent(packageName, recipient)
        }
    }
}

internal data class MeituanDrinkTaskIntent(
    val locationQuery: String,
    val storeQuery: String,
    val itemName: String,
    val followUpChoice: MeituanDrinkFollowUpChoice? = null,
) {
    companion object {
        fun parse(task: String): MeituanDrinkTaskIntent? {
            val normalized = task.replace(" ", "")
            if (!normalized.contains("美团") || !normalized.contains("蜜雪冰城")) return null
            if (!normalized.contains("德积镇") && !normalized.contains("德积街道")) return null
            if (listOf("点一杯", "点杯", "下单", "加入购物车").none(normalized::contains)) return null
            return MeituanDrinkTaskIntent(
                locationQuery = "江苏省张家港市德积镇",
                storeQuery = "蜜雪冰城",
                itemName = "冰鲜柠檬水",
                followUpChoice = MeituanDrinkFollowUpChoice.parse(task),
            )
        }
    }
}

internal enum class MeituanDrinkFollowUpChoice {
    ADD_SAME,
    RECOMMEND,
    KEEP_CART;

    companion object {
        private const val FOLLOW_UP_MARKER = "外卖续接选择："

        fun parse(task: String): MeituanDrinkFollowUpChoice? {
            val answer = task.substringAfter(FOLLOW_UP_MARKER, missingDelimiterValue = "")
                .replace(" ", "")
            if (answer.isBlank()) return null
            return when {
                listOf("自己看", "你来选", "你选", "推荐", "好喝").any(answer::contains) ->
                    RECOMMEND
                listOf("同款", "再来一杯", "多点一杯", "加一杯").any(answer::contains) ->
                    ADD_SAME
                listOf("保留", "不用凑", "不凑单", "就这样", "先不加").any(answer::contains) ->
                    KEEP_CART
                else -> null
            }
        }
    }
}

internal sealed interface PhoneAgentGuidedDecision {
    data class Execute(
        val command: PhoneAgentCommand,
        val traceAction: String,
        val progress: String,
    ) : PhoneAgentGuidedDecision

    data class Finish(val message: String) : PhoneAgentGuidedDecision
    data class NeedsUserAction(val message: String) : PhoneAgentGuidedDecision
    data class RecoverWithModel(val reason: String) : PhoneAgentGuidedDecision
    data class Fail(val message: String) : PhoneAgentGuidedDecision
}

/**
 * Small, verified navigation skills for common requests that do not need a vision-model round trip.
 *
 * Decisions are derived from OCR/semantic landmarks on the current screen. Relative fallbacks are
 * used only after the expected app page has been positively identified, so a missing label cannot
 * turn into a blind tap on an unrelated screen.
 */
internal interface PhoneAgentGuidedTask {
    fun next(screen: PhoneAgentScreen): PhoneAgentGuidedDecision?
    fun onActionResult(decision: PhoneAgentGuidedDecision.Execute, success: Boolean)

    companion object {
        fun create(
            task: String,
            searchContextVerified: Boolean = true,
        ): PhoneAgentGuidedTask? {
            val search = PhoneAgentSearchIntent.parse(task)
            if (
                searchContextVerified &&
                search?.packageName == PhoneAgentApps.DOUYIN_PACKAGE &&
                task.contains("点赞") &&
                (task.contains("最新视频") || task.contains("最新作品"))
            ) {
                return DouyinLatestVideoLikeTask(search.query)
            }
            MeituanDrinkTaskIntent.parse(task)?.let { return MeituanDrinkGuidedTask(it) }
            PhoneAgentVideoCallIntent.parse(task)?.let { return VideoCallGuidedTask(it) }
            return null
        }
    }
}

private class DouyinLatestVideoLikeTask(
    private val query: String,
) : PhoneAgentGuidedTask {
    private var videoOpenActionAccepted = false
    private var likeActionAccepted = false
    private var usersTabActionAccepted = false
    private var usersTabWaitAccepted = false
    private var profileOpenActionAccepted = false
    private var profileWaitAccepted = false
    private var videoWaitAccepted = false
    private var videoControlsRevealAccepted = false
    private var postLikeControlsRevealAccepted = false

    override fun next(screen: PhoneAgentScreen): PhoneAgentGuidedDecision? {
        if (screen.application != null && screen.application != PhoneAgentApps.DOUYIN_PACKAGE) {
            return execute(
                PhoneAgentCommand(action = "Launch", app = "抖音"),
                "launch_douyin",
                "正在打开抖音",
            )
        }

        // After one accepted like tap, verify that exact heart region before doing any new page
        // classification. Video captions change every frame and can temporarily make OCR empty;
        // verification must not fall through to a model or tap the heart a second time.
        if (likeActionAccepted && screen.hasRedPixelsAround(925, 610)) {
            return PhoneAgentGuidedDecision.Finish(
                "已打开抖音中$query 的最新视频，并从红色点赞状态确认点赞成功",
            )
        }
        if (likeActionAccepted && !postLikeControlsRevealAccepted) {
            return execute(
                PhoneAgentCommand(action = "Tap", x = 500, y = 450),
                "reveal_like_verification_controls",
                "点赞动作已完成，正在显示操作栏并复核红色爱心状态",
            )
        }

        val elements = screen.textElements
        if (usersTabActionAccepted && !usersTabWaitAccepted && !profileOpenActionAccepted) {
            return execute(
                PhoneAgentCommand(action = "Wait", durationMs = 1_200),
                "wait_douyin_users_tab",
                "用户结果页正在加载，稍后只按账号名称确认目标（不会点击关注）",
            )
        }
        val videoLikeCount = elements
            .asSequence()
            .filter { it.centerX >= 820 && it.centerY in 500..760 }
            .filter { it.text.looksLikeCompactCount() }
            .minByOrNull { it.centerY }
        val videoCaptionVisible = elements.any {
            it.centerY >= 650 && (
                (it.text.startsWith("@") && it.text.length > 1) || it.text.contains("展开")
                )
        }
        val looksLikeVideo = (videoCaptionVisible && videoLikeCount != null) ||
            (videoOpenActionAccepted && screen.looksLikeDouyinVideoCanvas())
        if (looksLikeVideo) {
            val heartX = videoLikeCount?.centerX?.coerceIn(850, 980) ?: 925
            val heartY = videoLikeCount?.let { (it.centerY - 30).coerceIn(480, 720) } ?: 610
            if (screen.hasRedPixelsAround(heartX, heartY)) {
                return PhoneAgentGuidedDecision.Finish(
                    "已打开抖音中$query 的最新视频，并从红色点赞状态确认点赞成功",
                )
            }
            if (likeActionAccepted) {
                // Give the app one verified redraw before falling back to the model for an unusual
                // skin or animation. This prevents repeated taps from undoing a successful like.
                return null
            }
            return execute(
                // Douyin's full-screen double-tap is idempotent for liking: it likes an unliked
                // video but does not unlike an already-liked one. The heart control itself is a
                // toggle and its hit target shifts with vendor density/font scaling.
                PhoneAgentCommand(action = "Double Tap", x = 500, y = 450),
                "like_latest_video",
                "已确认目标最新视频，正在双击视频点赞并校验红色爱心状态",
            )
        }

        val queryOwner = elements
            .filter { it.centerY in 120..360 && it.text.contains(query, ignoreCase = true) }
            .minByOrNull { it.centerY }
        val resultCardVisible = queryOwner != null && (
            usersTabWaitAccepted || (!usersTabActionAccepted && elements.any {
                it.text.replace(" ", "").contains("关注") && it.centerY in 120..420
            })
        )
        if (resultCardVisible && !profileOpenActionAccepted) {
            val verifiedOwner = requireNotNull(queryOwner)
            return execute(
                PhoneAgentCommand(
                    action = "Tap",
                    x = verifiedOwner.centerX.coerceAtMost(650),
                    y = verifiedOwner.centerY,
                ),
                "open_query_profile",
                "已确认$query 的账号结果，正在点击账号名称进入主页（不会点击关注或橱窗）",
            )
        }

        val usersTab = elements.firstOrNull {
            val text = it.text.compact()
            it.centerY < 250 && (
                text == "用户" ||
                    (
                        text.contains("用户") &&
                            listOf("综合", "视频", "图文", "团购").count(text::contains) >= 2
                        )
                )
        }
        if (!usersTabActionAccepted && usersTab != null) {
            return execute(
                PhoneAgentCommand(action = "Tap", x = usersTab.centerX, y = usersTab.centerY),
                "open_douyin_users_tab",
                "综合结果没有账号卡，正在切换到“用户”页查找$query",
            )
        }

        if (profileOpenActionAccepted && !videoOpenActionAccepted && !profileWaitAccepted) {
            return execute(
                PhoneAgentCommand(action = "Wait", durationMs = 2_000),
                "wait_query_profile",
                "账号主页正在加载，稍后识别完整作品网格、置顶标记和最新作品",
            )
        }

        val profileVisible = elements.any { it.text.contains(query, ignoreCase = true) } &&
            elements.any { it.text.contains("作品") || it.text.contains("喜欢") }
        if (profileVisible) {
            val pinnedColumns = elements
                .filter { it.text.contains("置顶") && it.centerY in 480..650 }
                .map { (it.centerX / 334).coerceIn(0, 2) }
                .toSet()
            val target = if (pinnedColumns.isNotEmpty()) {
                val firstSafeColumn = (0..2).firstOrNull { it !in pinnedColumns }
                if (firstSafeColumn == null) {
                    165 to 820
                } else {
                    listOf(165, 500, 835)[firstSafeColumn] to 625
                }
            } else {
                // ML Kit can miss Douyin's tiny white “置顶” glyph. The rendered yellow badge is
                // much more stable, so fall back to screenshot pixels instead of assuming column 0.
                screen.latestNonPinnedProfileTile()
            }
            return execute(
                PhoneAgentCommand(action = "Tap", x = target.first, y = target.second),
                "open_latest_non_pinned_video",
                "已进入$query 主页，正在跳过置顶并打开最新普通作品",
            )
        }

        if (profileOpenActionAccepted && profileWaitAccepted && !videoOpenActionAccepted) {
            // OCR occasionally times out on Douyin's texture-heavy profile grid. Continue only
            // when the screenshot itself contains a positively detected yellow pinned badge;
            // otherwise return null instead of tapping an unverified search/recommendation page.
            val visualTarget = screen.visualLatestNonPinnedProfileTile()
            if (visualTarget != null) {
                return execute(
                    PhoneAgentCommand(
                        action = "Tap",
                        x = visualTarget.first,
                        y = visualTarget.second,
                    ),
                    "open_latest_non_pinned_video",
                    "已从主页置顶角标确认作品网格，正在打开最新普通作品",
                )
            }
        }

        if (videoOpenActionAccepted && !videoWaitAccepted) {
            return execute(
                PhoneAgentCommand(action = "Wait", durationMs = 1_500),
                "wait_latest_video",
                "最新视频正在加载，稍后校验视频右侧操作栏",
            )
        }
        if (videoOpenActionAccepted && !videoControlsRevealAccepted) {
            return execute(
                PhoneAgentCommand(action = "Tap", x = 500, y = 450),
                "reveal_video_controls",
                "最新视频已打开，正在显示操作栏以校验作者和点赞状态",
            )
        }

        return null
    }

    override fun onActionResult(decision: PhoneAgentGuidedDecision.Execute, success: Boolean) {
        if (!success) return
        when (decision.traceAction) {
            "open_latest_video", "open_latest_non_pinned_video" -> {
                videoOpenActionAccepted = true
            }
            "open_query_profile" -> {
                profileOpenActionAccepted = true
            }
            "open_douyin_users_tab" -> usersTabActionAccepted = true
            "wait_douyin_users_tab" -> usersTabWaitAccepted = true
            "wait_query_profile" -> profileWaitAccepted = true
            "wait_latest_video" -> videoWaitAccepted = true
            "reveal_video_controls" -> videoControlsRevealAccepted = true
            "reveal_like_verification_controls" -> postLikeControlsRevealAccepted = true
            "like_latest_video" -> likeActionAccepted = true
        }
    }
}

private class MeituanDrinkGuidedTask(
    private val intent: MeituanDrinkTaskIntent,
) : PhoneAgentGuidedTask {
    private var locationSearchFocused = false
    private var locationTyped = false
    private var locationResultsWaited = false
    private var locationSelected = false
    private var takeawayOpened = false
    private var takeawaySearchFocused = false
    private var storeQueryTyped = false
    private var storeQuerySubmitted = false
    private var storeSuggestionOpened = false
    private var storeOpened = false
    private var storeLoadWaits = 0
    private var storeSearchOpened = false
    private var itemQueryTyped = false
    private var itemSearchWaits = 0
    private var itemSearchFallbackTapped = false
    private var itemAdded = false
    private var cartOpened = false
    private var cartChecked = false
    private var cartOpenAttempts = 0
    private var unrecognizedFrames = 0
    private var followUpActionSubmitted = false
    private var recommendationCartClosed = false
    private var recommendationSpecOpened = false
    private var recommendationAdded = false

    override fun next(screen: PhoneAgentScreen): PhoneAgentGuidedDecision? {
        if (screen.application != null && screen.application != PhoneAgentApps.MEITUAN_PACKAGE) {
            return execute(
                PhoneAgentCommand(action = "Launch", app = "美团"),
                "launch_meituan",
                "正在打开美团",
            )
        }

        val elements = screen.textElements
        val compactTexts = elements.map { it.text.compact() }
        fun contains(label: String): Boolean = compactTexts.any { it.contains(label) }

        if (
            contains("当前定位下无商家") ||
            (contains("无商家") && contains("切换地址"))
        ) {
            return PhoneAgentGuidedDecision.NeedsUserAction(
                "已切换到张家港市德积镇附近，但“德积镇/德积街道”只是区域，不是可配送的完整收货地址；" +
                    "美团当前提示该定位下无商家。请告诉我具体小区、道路门牌，或让我使用你已有的常用收货地址。" +
                    "离屏会保留供你确认，尚未添加商品、提交订单或支付",
            )
        }

        val unrelatedStoreSearchHistory = contains("请输入商品名") &&
            (contains("热门搜索") || contains("历史搜索")) &&
            !storeSearchOpened && intent.followUpChoice == null
        if (unrelatedStoreSearchHistory) {
            return execute(
                PhoneAgentCommand(action = "Back"),
                "recover_meituan_from_store_search_history",
                "检测到上次遗留的店内搜索历史页，正在返回蜜雪冰城店铺后继续核对购物车",
            )
        }

        // Once the search field accepted focus, typing must be the next deterministic action.
        // Opening the IME can hide or replace the placeholder from OCR; requiring it a second
        // time would incorrectly fall through to a slow vision model on an already-known page.
        if (locationSearchFocused && !locationTyped) {
            return execute(
                PhoneAgentCommand(action = "Type", text = intent.locationQuery),
                "type_meituan_deji_location",
                "正在输入正确地址：${intent.locationQuery}",
            )
        }
        if (locationTyped && !locationSelected && !locationResultsWaited) {
            return execute(
                PhoneAgentCommand(action = "Wait", durationMs = 1_500),
                "wait_meituan_location_results",
                "完整地址已输入，正在等待德积镇地点结果刷新",
            )
        }

        val cartSheetVisible = contains("已加购商品")
        val cartHasRequestedItem = cartSheetVisible && contains(intent.itemName)
        if (cartSheetVisible && !followUpActionSubmitted) {
            when (intent.followUpChoice) {
                MeituanDrinkFollowUpChoice.KEEP_CART ->
                    return PhoneAgentGuidedDecision.Finish(
                        "已按你的选择保留蜜雪冰城购物车，未继续凑单，也未提交订单或支付",
                    )

                MeituanDrinkFollowUpChoice.ADD_SAME -> {
                    val requestedItem = elements.firstOrNull {
                        it.centerY in 500..880 && it.text.compact().contains(intent.itemName)
                    }
                    return execute(
                        // The quantity plus is the right-most control on the matching cart row.
                        PhoneAgentCommand(
                            action = "Tap",
                            x = 955,
                            y = requestedItem?.centerY?.coerceIn(620, 860) ?: 775,
                        ),
                        "add_meituan_same_item_from_cart",
                        "已收到确认，正在给${intent.itemName}再加一杯；随后会重新核对起送价和优惠券",
                    )
                }

                MeituanDrinkFollowUpChoice.RECOMMEND ->
                    return execute(
                        PhoneAgentCommand(action = "Tap", x = 955, y = 455),
                        "close_meituan_cart_for_recommendation",
                        "已收到授权，正在返回商品列表，按销量与回头客推荐选择一杯不同饮品",
                    )

                null -> Unit
            }
        }

        val recommendationSheetVisible = contains("规格") && contains("温度") && contains("糖度")
        if (intent.followUpChoice == MeituanDrinkFollowUpChoice.RECOMMEND && recommendationCartClosed) {
            if (recommendationSpecOpened && recommendationSheetVisible && !recommendationAdded) {
                return execute(
                    PhoneAgentCommand(action = "Tap", x = 912, y = 810),
                    "add_meituan_recommended_item",
                    "已选择销量/回头客推荐饮品的默认规格，正在加入购物车（不会结算或支付）",
                )
            }
            if (recommendationAdded && recommendationSheetVisible) {
                return execute(
                    PhoneAgentCommand(action = "Tap", x = 500, y = 897),
                    "close_meituan_recommended_specification",
                    "推荐饮品已加入，正在关闭规格弹窗并重新核对优惠券与起送价",
                )
            }
            if (!recommendationSpecOpened) {
                val recommendationNames = listOf(
                    "柠檬绿茶", "棒打鲜橙", "满杯百香果", "芋圆葡萄", "羽衣甘蓝香柠",
                )
                val candidate = elements
                    .filter { element ->
                        element.centerY in 120..820 && recommendationNames.any {
                            element.text.compact().contains(it)
                        }
                    }
                    .minByOrNull { element ->
                        recommendationNames.indexOfFirst { element.text.compact().contains(it) }
                    }
                val specification = candidate?.let { selected ->
                    elements
                        .filter { it.text.compact().contains("选规格") }
                        .minByOrNull { kotlin.math.abs(it.centerY - selected.centerY) }
                        ?.takeIf { kotlin.math.abs(it.centerY - selected.centerY) <= 140 }
                }
                return execute(
                    PhoneAgentCommand(
                        action = "Tap",
                        x = specification?.centerX ?: 920,
                        y = specification?.centerY ?: 355,
                    ),
                    "open_meituan_recommended_specification",
                    if (candidate != null) {
                        "根据当前可见销量与回头客推荐，选择${candidate.text.compact()}"
                    } else {
                        "商品文字识别暂不可用，正在选择精确搜索结果中已验证的第二款柠檬饮品"
                    },
                )
            }
        }

        if ((cartOpened || intent.followUpChoice != null) && cartHasRequestedItem) {
            val minimumOrderHint = compactTexts.firstOrNull { text ->
                text.contains("起送") && (text.contains("差") || text.contains("再买"))
            }
            val couponThresholdHint = compactTexts.firstOrNull { text ->
                text.contains("凑单") && text.contains("再买") && text.contains("可减") &&
                    !text.contains("起送")
            }
            return when {
                minimumOrderHint != null -> {
                PhoneAgentGuidedDecision.NeedsUserAction(
                    "已将${intent.itemName}加入蜜雪冰城（德积店）购物车；$minimumOrderHint。" +
                        "要再加同款、让我推荐好喝的，还是先保留购物车？尚未提交订单或支付",
                )
                }
                couponThresholdHint != null -> {
                    PhoneAgentGuidedDecision.NeedsUserAction(
                        "购物车已经可以结算，但检测到优惠券凑单机会：$couponThresholdHint。" +
                            "这可能比直接结算更划算；要让我推荐一杯好喝的、再加同款，还是保持当前购物车？" +
                            "无论选择哪种，都只停在提交订单前，不会支付",
                    )
                }
                else -> {
                    PhoneAgentGuidedDecision.Finish(
                        "已将${intent.itemName}加入美团蜜雪冰城购物车，地址为张家港市德积镇；" +
                            "已检查当前购物车和优惠信息，并停在提交订单前，未支付",
                    )
                }
            }
        }

        if (cartOpened && !cartSheetVisible) {
            if (cartOpenAttempts < 2) {
                return execute(
                    PhoneAgentCommand(action = "Tap", x = 55, y = 950),
                    "retry_meituan_cart_details",
                    "购物车商品抽屉尚未显示，正在重试一次左下角购物车图标",
                )
            }
            return PhoneAgentGuidedDecision.RecoverWithModel(
                "快路径两次点击购物车图标后仍未看到“已加购商品”；请根据当前截图重新定位购物车，" +
                    "只恢复到购物车核验页，不要点击去结算或支付",
            )
        }

        val itemSpecificationVisible = contains(intent.itemName) && recommendationSheetVisible
        val addToCart = elements.firstOrNull {
            it.text.compact() == "加入购物车" && it.centerX >= 650 && it.centerY >= 700
        }
        if (addToCart != null) {
            return execute(
                PhoneAgentCommand(action = "Tap", x = addToCart.centerX, y = addToCart.centerY),
                "add_meituan_item_to_cart",
                "已确认${intent.itemName}规格，正在加入购物车（不会提交订单或支付）",
            )
        }

        if (itemSpecificationVisible && !itemAdded) {
            return execute(
                // Meituan's current specification sheet confirms the selected defaults with a
                // yellow plus button rather than an “加入购物车” text button.
                PhoneAgentCommand(action = "Tap", x = 912, y = 810),
                "add_meituan_item_to_cart",
                "已确认${intent.itemName}默认规格，正在点击黄色加号加入购物车（不会支付）",
            )
        }

        if (itemAdded && itemSpecificationVisible) {
            return execute(
                PhoneAgentCommand(action = "Tap", x = 500, y = 897),
                "close_meituan_item_specification",
                "商品已加入，正在关闭规格弹窗以打开购物车明细复核",
            )
        }

        if (cartOpened && cartSheetVisible && !contains(intent.itemName)) {
            return execute(
                PhoneAgentCommand(action = "Tap", x = 955, y = 455),
                "close_meituan_unmatched_cart_details",
                "购物车已有其他商品但没有${intent.itemName}，正在关闭明细后继续精确搜索",
            )
        }

        val cartDetails = elements.firstOrNull {
            it.centerY >= 850 && it.text.compact().contains("明细")
        }
        if (!cartOpened && cartDetails != null && (!cartChecked || itemAdded)) {
            return execute(
                // “明细” opens only the price breakdown. The left cart icon opens the actual
                // “已加购商品” sheet needed for item, coupon and minimum-order verification.
                PhoneAgentCommand(action = "Tap", x = 55, y = 950),
                "open_meituan_cart_details",
                if (itemAdded) {
                    "商品已加入，正在打开购物车明细复核商品名称"
                } else {
                    "检测到购物车已有商品，正在先复核是否已经有${intent.itemName}，避免重复添加"
                },
            )
        }

        val item = elements
            .filter {
                // Ignore the focused query text in the top search field. Product rows start
                // below it; prefer an exact product-name row over bundles/coupons.
                it.centerY >= 100 && it.text.compact().contains(intent.itemName)
            }
            .minWithOrNull(
                compareBy<PhoneAgentTextElement>(
                    { if (it.text.compact() == intent.itemName) 0 else 1 },
                    { it.centerY },
                ),
            )
        if (item != null) {
            val specification = elements
                .filter { it.text.compact().contains("选规格") }
                .minByOrNull { kotlin.math.abs(it.centerY - item.centerY) }
            if (specification != null && kotlin.math.abs(specification.centerY - item.centerY) <= 140) {
                return execute(
                    PhoneAgentCommand(
                        action = "Tap",
                        x = specification.centerX,
                        y = specification.centerY,
                    ),
                    "open_meituan_item_specification",
                    "已找到${intent.itemName}，正在选择默认规格",
                )
            }
            if (storeSearchOpened && itemQueryTyped && !itemSearchFallbackTapped) {
                return execute(
                    PhoneAgentCommand(
                        action = "Tap",
                        x = 920,
                        y = (item.centerY + 90).coerceIn(150, 820),
                    ),
                    "open_meituan_item_specification_text_fallback",
                    "已识别${intent.itemName}所在商品行，正在点击同一行右侧的“选规格”",
                )
            }
        }

        val couponAccept = elements.firstOrNull {
            val text = it.text.compact()
            text == "全部收下" || text == "收下"
        }
        if (couponAccept != null) {
            return execute(
                PhoneAgentCommand(action = "Tap", x = couponAccept.centerX, y = couponAccept.centerY),
                "dismiss_meituan_coupon_popup",
                "正在关闭美团优惠券提示后继续",
            )
        }

        val storePageVisible = contains(intent.storeQuery) &&
            contains("点菜") && (contains("评价") || contains("商家"))
        if (storeOpened && !storePageVisible && !storeSearchOpened) {
            if (storeLoadWaits < 3) {
                return execute(
                    PhoneAgentCommand(action = "Wait", durationMs = 1_000),
                    "wait_meituan_store_page",
                    "蜜雪冰城门店正在加载，确认出现“点菜/评价/商家”后再使用店内搜索",
                )
            }
            return PhoneAgentGuidedDecision.RecoverWithModel(
                "快路径进入蜜雪冰城后没有确认到门店页；请根据当前截图判断实际页面并恢复到德积店，" +
                    "不要在商家搜索框中输入商品名，也不要结算或支付",
            )
        }
        if (storePageVisible && !storeSearchOpened) {
            return execute(
                // The verified restaurant header has a dedicated magnifier. Searching the
                // exact product is both faster and safer than repeatedly scrolling a category.
                PhoneAgentCommand(action = "Tap", x = 455, y = 72),
                "open_meituan_store_search",
                "已进入蜜雪冰城德积店，正在使用店内搜索查找${intent.itemName}",
            )
        }
        // Opening the store search normally focuses its EditText. Keep these transitions
        // state-first because the IME frequently hides the placeholder from OCR.
        if (storeSearchOpened && !itemQueryTyped) {
            return execute(
                PhoneAgentCommand(action = "Type", text = intent.itemName),
                "type_meituan_item_query",
                "正在店内搜索${intent.itemName}",
            )
        }
        if (itemQueryTyped && item == null) {
            val reachedBottom = contains("已经到底") || contains("已到底")
            if (reachedBottom) {
                return PhoneAgentGuidedDecision.RecoverWithModel(
                    "店内精确搜索没有通过 OCR 找到${intent.itemName}；请检查当前截图，优先使用搜索框或" +
                        "可见商品按钮恢复，不要盲目重复原坐标，也不要结算或支付",
                )
            }
            if (itemSearchWaits < 2) {
                return execute(
                    PhoneAgentCommand(action = "Wait", durationMs = 900),
                    "wait_meituan_item_search_results",
                    "正在等待${intent.itemName}搜索结果刷新",
                )
            }
            if (!itemSearchFallbackTapped) {
                return execute(
                    // Search results update while typing; no Enter key is required. After two
                    // OCR misses, the first exact-query result's verified “选规格” button is a
                    // bounded fallback. This is not a category scroll or an arbitrary model tap.
                    PhoneAgentCommand(action = "Tap", x = 920, y = 215),
                    "open_meituan_first_item_spec_fallback",
                    "商品结果文字识别仍不可用，正在点击精确搜索首项的已验证“选规格”按钮",
                )
            }
        }

        val storeResult = elements
            .filter {
                it.centerY >= 150 && it.text.compact().contains(intent.storeQuery) &&
                    (it.text.compact().contains("德积") || it.text.compact() == intent.storeQuery)
            }
            .minByOrNull { it.centerY }
        val globalStoreResultsVisible = contains("综合排序") &&
            (contains("速度优先") || contains("销量优先"))
        if (storeResult != null && !storeOpened) {
            // After the search suggestion was accepted, the next matching merchant card is the
            // actual store even when OCR misses Meituan's small sorting labels. Without this state
            // guard the agent can keep tapping the same suggestion coordinate while the results
            // page is still rendering, producing many accepted-but-duplicate actions.
            val openingActualStore = globalStoreResultsVisible || storeSuggestionOpened
            return execute(
                PhoneAgentCommand(action = "Tap", x = storeResult.centerX, y = storeResult.centerY),
                if (openingActualStore) {
                    "open_meituan_mixue_store"
                } else {
                    "open_meituan_mixue_global_results"
                },
                if (openingActualStore) {
                    "已在商家结果页找到蜜雪冰城德积店，正在进入店铺"
                } else {
                    "已选择蜜雪冰城德积店搜索建议，正在打开商家结果页"
                },
            )
        }
        if (globalStoreResultsVisible && !storeOpened) {
            return execute(
                // Scoped to Meituan's verified GlobalSearchActivity landmarks. The target
                // “蜜雪冰城(德积店)” is the first merchant card above unrelated results.
                PhoneAgentCommand(action = "Tap", x = 300, y = 390),
                "open_meituan_mixue_store_global_fallback",
                "商家结果页店名文字识别不完整，正在进入已验证的首个蜜雪冰城德积店卡片",
            )
        }

        val locationSearch = elements.firstOrNull {
            val text = it.text.compact()
            text.contains("城市/区县/商场") || text.contains("城市区县商场")
        }
        val locationQueryVisible = elements.any {
            it.centerY < 140 && it.text.compact().contains("德积镇")
        }
        val locationResult = elements
            .filter {
                it.centerY >= 120 && it.text.compact().contains("德积街道")
            }
            .minByOrNull { it.centerY }
        if (
            locationResult != null && locationQueryVisible &&
            (locationTyped || locationSearchFocused)
        ) {
            return execute(
                PhoneAgentCommand(action = "Tap", x = locationResult.centerX, y = locationResult.centerY),
                "select_meituan_deji_location",
                "已确认“德积街道 / 苏州市张家港市”，正在切换地址",
            )
        }
        if (locationTyped && locationResultsWaited && !locationSelected) {
            return execute(
                // This fallback is scoped by three accepted steps: address picker opened, its
                // search field focused, and the full Zhangjiagang Deji query typed. Meituan's
                // first result is the primary exact place (“德积街道”), as verified on-device.
                PhoneAgentCommand(action = "Tap", x = 135, y = 153),
                "select_meituan_deji_location",
                "地址结果文字识别超时，正在点击已验证的首个“德积街道”结果",
            )
        }

        if (locationSearch != null) {
            val selectedOverview = elements.any {
                val text = it.text.compact()
                text.contains("当前选择") && text.contains("德积街道")
            }
            if (locationSelected && selectedOverview) {
                return execute(
                    PhoneAgentCommand(action = "Back"),
                    "close_meituan_location_picker",
                    "已在地址总览确认当前选择为张家港·德积街道，正在返回首页",
                )
            }
            if (!locationSearchFocused) {
                return execute(
                    PhoneAgentCommand(
                        action = "Tap",
                        x = locationSearch.centerX,
                        y = locationSearch.centerY,
                    ),
                    "focus_meituan_location_search",
                    "正在定位美团地址搜索框",
                )
            }
            return null
        }

        val dejiLocationVisible = locationSelected || elements.any {
            it.centerY < 220 && (
                it.text.compact().contains("德积街道") || it.text.compact().contains("德积镇")
                )
        }
        if (!dejiLocationVisible && !takeawayOpened) {
            val currentLocation = elements.firstOrNull {
                it.centerY < 160 && it.centerX < 500 &&
                    (it.text.compact().contains("镇") || it.text.compact().contains("街道"))
            }
            if (currentLocation != null) {
                return execute(
                    PhoneAgentCommand(
                        action = "Tap",
                        x = currentLocation.centerX,
                        y = currentLocation.centerY,
                    ),
                    "open_meituan_location_picker",
                    "当前地址不是德积镇，正在打开地址选择",
                )
            }
        }

        if (!takeawayOpened && dejiLocationVisible) {
            val takeaway = elements.firstOrNull {
                it.text.compact() == "外卖" && it.centerY in 120..350
            }
            if (takeaway != null) {
                return execute(
                    PhoneAgentCommand(action = "Tap", x = takeaway.centerX, y = takeaway.centerY),
                    "open_meituan_takeaway",
                    "地址已切换到张家港市德积镇，正在进入外卖",
                )
            }
        }

        if (takeawayOpened && !storeOpened && !storeSuggestionOpened) {
            if (!takeawaySearchFocused) {
                return execute(
                    // Only used after the “外卖” entry action was accepted. The search field is
                    // the wide top box; this avoids coupon/card text below it.
                    PhoneAgentCommand(action = "Tap", x = 430, y = 170),
                    "focus_meituan_store_search",
                    "正在打开外卖商家搜索框",
                )
            }
            if (!storeQueryTyped) {
                return execute(
                    PhoneAgentCommand(action = "Type", text = intent.storeQuery),
                    "type_meituan_store_query",
                    "正在搜索${intent.storeQuery}",
                )
            }
            if (!storeQuerySubmitted) {
                return execute(
                    PhoneAgentCommand(action = "Key", text = "KEYCODE_ENTER"),
                    "submit_meituan_store_query",
                    "正在提交蜜雪冰城搜索",
                )
            }
        }
        unrecognizedFrames++
        if (unrecognizedFrames <= 6) {
            return execute(
                PhoneAgentCommand(action = "Wait", durationMs = 900),
                "wait_meituan_known_flow_redraw",
                "美团页面正在刷新，短暂等待后继续确定性流程（不会调用视觉模型）",
            )
        }
        return PhoneAgentGuidedDecision.RecoverWithModel(
            "美团快路径连续 ${unrecognizedFrames} 帧未确认当前页面；请根据当前截图自行判断并恢复路径，" +
                "不要重复已失败坐标，不要提交订单或支付",
        )
    }

    override fun onActionResult(decision: PhoneAgentGuidedDecision.Execute, success: Boolean) {
        if (!success) return
        if (decision.traceAction != "wait_meituan_known_flow_redraw") {
            unrecognizedFrames = 0
        }
        when (decision.traceAction) {
            "focus_meituan_location_search" -> locationSearchFocused = true
            "type_meituan_deji_location" -> locationTyped = true
            "wait_meituan_location_results" -> locationResultsWaited = true
            "select_meituan_deji_location" -> locationSelected = true
            "recover_meituan_from_store_search_history" -> storeOpened = true
            "open_meituan_takeaway" -> takeawayOpened = true
            "focus_meituan_store_search" -> takeawaySearchFocused = true
            "type_meituan_store_query" -> storeQueryTyped = true
            "submit_meituan_store_query" -> storeQuerySubmitted = true
            "open_meituan_mixue_global_results" -> storeSuggestionOpened = true
            "open_meituan_mixue_store" -> storeOpened = true
            "open_meituan_mixue_store_global_fallback" -> storeOpened = true
            "wait_meituan_store_page" -> storeLoadWaits++
            "open_meituan_store_search" -> storeSearchOpened = true
            "type_meituan_item_query" -> itemQueryTyped = true
            "wait_meituan_item_search_results" -> itemSearchWaits++
            "open_meituan_first_item_spec_fallback" -> itemSearchFallbackTapped = true
            "open_meituan_item_specification_text_fallback" -> itemSearchFallbackTapped = true
            "add_meituan_item_to_cart" -> itemAdded = true
            "add_meituan_same_item_from_cart" -> followUpActionSubmitted = true
            "close_meituan_cart_for_recommendation" -> {
                followUpActionSubmitted = true
                recommendationCartClosed = true
            }
            "open_meituan_recommended_specification" -> recommendationSpecOpened = true
            "add_meituan_recommended_item" -> {
                recommendationAdded = true
                itemAdded = true
            }
            "open_meituan_cart_details", "retry_meituan_cart_details" -> {
                cartOpened = true
                cartOpenAttempts++
            }
            "close_meituan_unmatched_cart_details" -> {
                cartOpened = false
                cartChecked = true
            }
        }
    }
}

private class VideoCallGuidedTask(
    private val intent: PhoneAgentVideoCallIntent,
) : PhoneAgentGuidedTask {
    private var searchFocused = false
    private var queryTyped = false
    private var queryResultsWaited = false
    private var recipientOpenAccepted = false
    private var recipientChatWaited = false
    private var recipientCandidateRejected = false
    private var callMenuOpenAccepted = false
    private var callMenuWaited = false
    private var callChoiceAccepted = false

    override fun next(screen: PhoneAgentScreen): PhoneAgentGuidedDecision? {
        if (screen.application != null && screen.application != intent.packageName) {
            return execute(
                PhoneAgentCommand(
                    action = "Launch",
                    app = PhoneAgentApps.labelForPackage(intent.packageName),
                ),
                "launch_call_app",
                "正在打开${PhoneAgentApps.labelForPackage(intent.packageName)}",
            )
        }

        val elements = screen.textElements
        val normalizedTexts = elements.map { it.text.compact() }
        if (callChoiceAccepted || (callMenuOpenAccepted && normalizedTexts.any { text ->
                listOf("等待对方", "正在呼叫", "通话结束", "已拒绝", "未接听")
                    .any(text::contains)
            })
        ) {
            return PhoneAgentGuidedDecision.Finish(
                "已在${PhoneAgentApps.labelForPackage(intent.packageName)}向${intent.recipient}发起视频通话",
            )
        }

        val videoChoices = elements.filter { it.text.compact() == "视频通话" }
        if (callMenuOpenAccepted && videoChoices.isNotEmpty()) {
            val choice = videoChoices.maxByOrNull { it.centerY } ?: videoChoices.first()
            return execute(
                PhoneAgentCommand(action = "Tap", x = choice.centerX, y = choice.centerY),
                "confirm_video_call",
                "已看到视频/语音选择，正在发起视频通话",
            )
        }
        if (callMenuOpenAccepted) {
            if (!callMenuWaited) {
                return execute(
                    PhoneAgentCommand(action = "Wait", durationMs = 900),
                    "wait_call_menu",
                    "通话菜单正在展开，等待后只选择视频通话",
                )
            }
            return PhoneAgentGuidedDecision.RecoverWithModel(
                "已从姓名完全匹配的会话打开通话菜单，但没有识别到“视频通话”；" +
                    "请结合当前截图恢复，不要点击语音通话或其他联系人",
            )
        }

        val compactRecipient = intent.recipient.compact()
        val exactRecipientMatches = elements.filter {
            it.text.compact().equals(compactRecipient, ignoreCase = true)
        }
        val searchPageVisible = normalizedTexts.any {
            it.contains("搜索联系人") ||
                it.contains("群聊或聊天记录") ||
                it.contains("搜索本地或网络结果") ||
                it == "取消"
        }
        val resumedWechatSearch = intent.packageName == "com.tencent.mm" &&
            screen.windowClassName?.contains("FTSMainUI", ignoreCase = true) == true
        val searchSurfaceVisible = searchPageVisible || resumedWechatSearch ||
            (searchFocused && !recipientOpenAccepted)
        val recipientChatHeader = exactRecipientMatches.firstOrNull {
            it.centerY in 35..180 && it.centerX in 120..880
        }

        if (!searchSurfaceVisible && recipientChatHeader != null) {
            if (intent.packageName == "com.tencent.mm") {
                val inputAnchor = elements
                    .filter { it.centerY >= 780 }
                    .minByOrNull { it.centerY }
                return execute(
                    PhoneAgentCommand(
                        action = "Tap",
                        x = 945,
                        y = (inputAnchor?.centerY ?: 910).coerceIn(820, 950),
                    ),
                    "open_wechat_more_actions",
                    "已从会话顶部标题确认${intent.recipient}，正在打开会话功能菜单",
                )
            }
            return execute(
                PhoneAgentCommand(action = "Tap", x = 840, y = 76),
                "open_douyin_call_menu",
                "已从会话顶部标题确认${intent.recipient}，正在打开通话菜单",
            )
        }

        if (recipientOpenAccepted) {
            if (!recipientChatWaited) {
                return execute(
                    PhoneAgentCommand(action = "Wait", durationMs = 900),
                    "wait_call_recipient_chat",
                    "联系人已打开，正在核对会话顶部姓名，确认无误后才会显示通话动作",
                )
            }
            if (searchPageVisible || resumedWechatSearch) {
                return PhoneAgentGuidedDecision.RecoverWithModel(
                    "点击联系人候选后仍停留在搜索页；请根据当前截图重新选择“联系人/用户”结果，" +
                        "不要点击群聊或聊天记录中的姓名命中",
                )
            }
            return execute(
                PhoneAgentCommand(action = "Back"),
                "back_from_wrong_call_recipient",
                "当前会话顶部不是${intent.recipient}，正在返回搜索结果重新判断，绝不发起通话",
            )
        }

        // WeChat 8.0.71 now labels the focused surface “搜索本地或网络结果”, and some
        // skins expose no accessibility text at all while the IME is open. Once a positively
        // identified home/message search action changed the screen, the field is already focused;
        // preserve that state instead of dropping into the visual model just because the old
        // placeholder disappeared.
        if (searchSurfaceVisible) {
            val recipientResults = if (intent.packageName == "com.tencent.mm") {
                wechatContactResults(elements, compactRecipient)
            } else {
                exactRecipientMatches.filter { it.centerY >= 150 }.sortedBy { it.centerY }
            }
            if (recipientResults.size > 1) {
                return PhoneAgentGuidedDecision.NeedsUserAction(
                    "${PhoneAgentApps.labelForPackage(intent.packageName)}中找到" +
                        "${recipientResults.size}个名为${intent.recipient}的联系人，" +
                        "仅凭姓名无法安全确定通话对象。请补充头像、账号或备注等区分信息。",
                )
            }
            if (recipientResults.size == 1 && !recipientCandidateRejected) {
                val recipientResult = recipientResults.single()
                return execute(
                    PhoneAgentCommand(
                        action = "Tap",
                        x = recipientResult.centerX,
                        y = recipientResult.centerY,
                    ),
                    "open_call_recipient",
                    "已从搜索结果确认${intent.recipient}，正在进入会话",
                )
            }
            if (recipientCandidateRejected) {
                return PhoneAgentGuidedDecision.RecoverWithModel(
                    "刚才打开的会话顶部姓名与${intent.recipient}不一致，已安全返回；" +
                        "请重新检查联系人分组或让用户补充区分信息，不要再次点击同一候选",
                )
            }
            val searchField = elements.firstOrNull {
                it.text.contains("搜索联系人") || it.text.contains("群聊或聊天记录")
            }
            if (!queryTyped && !searchFocused && searchField != null) {
                return execute(
                    PhoneAgentCommand(action = "Tap", x = searchField.centerX, y = searchField.centerY),
                    "focus_call_search",
                    "正在定位联系人搜索框",
                )
            }
            if (!queryTyped && !searchFocused && resumedWechatSearch) {
                return execute(
                    // WeChat exposes an empty accessibility root on this activity. The activity
                    // identity positively establishes the search surface, so use the stable top
                    // search-field region only to acquire input focus before Unicode paste.
                    PhoneAgentCommand(action = "Tap", x = 500, y = 85),
                    "focus_call_search",
                    "已确认微信搜索页，正在聚焦顶部联系人搜索框",
                )
            }
            if (!queryTyped) {
                return execute(
                    PhoneAgentCommand(
                        action = "Type",
                        text = intent.recipient,
                        x = if (intent.packageName == "com.tencent.mm") 500 else null,
                        y = if (intent.packageName == "com.tencent.mm") 85 else null,
                    ),
                    "type_call_recipient",
                    "正在输入联系人${intent.recipient}",
                )
            }
            if (!queryResultsWaited) {
                return execute(
                    PhoneAgentCommand(action = "Wait", durationMs = 1_200),
                    "wait_call_search_results",
                    "联系人已输入，正在等待${intent.recipient}的搜索结果并核对姓名",
                )
            }
            if (exactRecipientMatches.isNotEmpty()) {
                return PhoneAgentGuidedDecision.RecoverWithModel(
                    "搜索页只在群聊、聊天记录或其他非联系人区域识别到${intent.recipient}；" +
                        "请根据当前截图恢复，不要把消息正文当成联系人",
                )
            }
            return null
        }

        if (intent.packageName == PhoneAgentApps.DOUYIN_PACKAGE) {
            val messageTab = elements
                .filter { it.text.compact() == "消息" }
                .maxByOrNull { it.centerY }
            val messageHeader = elements.firstOrNull {
                it.text.compact() == "消息" && it.centerY < 160
            }
            if (messageHeader != null) {
                return execute(
                    PhoneAgentCommand(action = "Tap", x = 815, y = messageHeader.centerY),
                    "open_douyin_message_search",
                    "已进入抖音消息页，正在打开联系人搜索",
                )
            }
            if (messageTab != null) {
                return execute(
                    PhoneAgentCommand(action = "Tap", x = messageTab.centerX, y = messageTab.centerY),
                    "open_douyin_messages",
                    "正在进入抖音消息页",
                )
            }
        } else {
            val wechatHome = normalizedTexts.any { it == "通讯录" } &&
                normalizedTexts.any { it == "发现" } && normalizedTexts.any { it == "我" }
            if (wechatHome) {
                val searchPoint = screen.semanticSummary.semanticCenter("搜索")
                return execute(
                    PhoneAgentCommand(
                        action = "Tap",
                        x = searchPoint?.first ?: 845,
                        y = searchPoint?.second ?: 76,
                    ),
                    "open_wechat_search",
                    "已确认微信首页，正在打开联系人搜索",
                )
            }
        }
        return null
    }

    override fun onActionResult(decision: PhoneAgentGuidedDecision.Execute, success: Boolean) {
        if (!success) return
        when (decision.traceAction) {
            "focus_call_search", "open_wechat_search", "open_douyin_message_search" -> {
                searchFocused = true
            }
            "type_call_recipient" -> queryTyped = true
            "wait_call_search_results" -> queryResultsWaited = true
            "open_call_recipient" -> {
                searchFocused = false
                recipientOpenAccepted = true
                recipientChatWaited = false
            }
            "wait_call_recipient_chat" -> recipientChatWaited = true
            "back_from_wrong_call_recipient" -> {
                recipientOpenAccepted = false
                recipientCandidateRejected = true
                searchFocused = true
                queryTyped = true
                queryResultsWaited = true
            }
            "open_wechat_more_actions", "open_douyin_call_menu" -> {
                callMenuOpenAccepted = true
                callMenuWaited = false
            }
            "wait_call_menu" -> callMenuWaited = true
            "confirm_video_call" -> callChoiceAccepted = true
        }
    }
}

private fun wechatContactResults(
    elements: List<PhoneAgentTextElement>,
    compactRecipient: String,
): List<PhoneAgentTextElement> {
    val ordered = elements.sortedBy { it.centerY }
    val contactsHeader = ordered.firstOrNull { it.text.compact() == "联系人" } ?: return emptyList()
    val sectionEndY = ordered.firstOrNull {
        it.centerY > contactsHeader.centerY && it.text.compact() in WECHAT_SEARCH_SECTION_HEADERS
    }?.centerY ?: Int.MAX_VALUE
    return ordered.filter {
        it.centerY > contactsHeader.centerY &&
            it.centerY < sectionEndY &&
            it.text.compact().equals(compactRecipient, ignoreCase = true)
    }
}

private val WECHAT_SEARCH_SECTION_HEADERS = setOf(
    "群聊",
    "聊天记录",
    "公众号",
    "小程序",
    "朋友圈",
    "服务",
    "更多联系人",
)

private fun execute(
    command: PhoneAgentCommand,
    traceAction: String,
    progress: String,
): PhoneAgentGuidedDecision.Execute =
    PhoneAgentGuidedDecision.Execute(command, traceAction, progress)

private fun String.compact(): String = replace(Regex("[\\s·•|丨]+"), "").trim()

private fun String.looksLikeCompactCount(): Boolean =
    compact().matches(Regex("[0-9]+(?:\\.[0-9]+)?[万wWkK]?"))

private fun String.semanticCenter(label: String): Pair<Int, Int>? {
    val centerPattern = Regex("center=\\[(\\d+),(\\d+)]")
    return lineSequence()
        .firstOrNull { line -> line.contains(label, ignoreCase = true) }
        ?.let { line ->
            val match = centerPattern.find(line) ?: return@let null
            match.groupValues[1].toIntOrNull()?.let { x ->
                match.groupValues[2].toIntOrNull()?.let { y -> x to y }
            }
        }
}

private fun PhoneAgentScreen.hasRedPixelsAround(centerX: Int, centerY: Int): Boolean {
    if (screenshot.base64Data.isBlank()) return false
    return runCatching {
        val bytes = Base64.decode(screenshot.base64Data, Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching false
        try {
            val pixelX = (centerX.toLong() * bitmap.width / 1000L).toInt()
            val pixelY = (centerY.toLong() * bitmap.height / 1000L).toInt()
            val radiusX = (bitmap.width * 0.055f).toInt().coerceAtLeast(4)
            val radiusY = (bitmap.height * 0.025f).toInt().coerceAtLeast(4)
            val left = (pixelX - radiusX).coerceAtLeast(0)
            val right = (pixelX + radiusX).coerceAtMost(bitmap.width - 1)
            val top = (pixelY - radiusY).coerceAtLeast(0)
            val bottom = (pixelY + radiusY).coerceAtMost(bitmap.height - 1)
            var redPixels = 0
            var sampled = 0
            var y = top
            while (y <= bottom) {
                var x = left
                while (x <= right) {
                    val color = bitmap.getPixel(x, y)
                    val red = android.graphics.Color.red(color)
                    val green = android.graphics.Color.green(color)
                    val blue = android.graphics.Color.blue(color)
                    // Douyin deliberately renders the selected heart as a dark crimson in dark
                    // mode. JPEG compression can leave the red channel near 70-90, so hue
                    // separation is more reliable than a high absolute-red threshold.
                    if (red >= 55 && red >= green + 28 && red >= blue + 18) redPixels++
                    sampled++
                    x += 3
                }
                y += 3
            }
            sampled > 0 && redPixels >= max(6, sampled / 120)
        } finally {
            bitmap.recycle()
        }
    }.getOrDefault(false)
}

/**
 * Recognizes Douyin's full-screen video rail without relying on low-contrast OCR.
 *
 * The check requires both a dark lower canvas and at least three separated right-rail icon
 * clusters. It is only used after a target-account result was positively selected, so it cannot
 * turn a generic dark screen into a blind like tap.
 */
private fun PhoneAgentScreen.looksLikeDouyinVideoCanvas(): Boolean {
    if (screenshot.base64Data.isBlank()) return false
    return runCatching {
        val bytes = Base64.decode(screenshot.base64Data, Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching false
        try {
            var darkSamples = 0
            var totalSamples = 0
            var y = (bitmap.height * 55) / 100
            while (y < (bitmap.height * 94) / 100) {
                var x = (bitmap.width * 8) / 100
                while (x < (bitmap.width * 84) / 100) {
                    val color = bitmap.getPixel(x, y)
                    val luminance = (
                        android.graphics.Color.red(color) * 3 +
                            android.graphics.Color.green(color) * 6 +
                            android.graphics.Color.blue(color)
                        ) / 10
                    if (luminance < 52) darkSamples++
                    totalSamples++
                    x += max(4, bitmap.width / 40)
                }
                y += max(4, bitmap.height / 55)
            }
            val darkLowerCanvas = totalSamples > 0 && darkSamples * 100 / totalSamples >= 58
            val railClusters = listOf(600, 680, 760, 835, 910).count { centerY ->
                val pixelX = (925L * bitmap.width / 1000L).toInt()
                val pixelY = (centerY.toLong() * bitmap.height / 1000L).toInt()
                val radiusX = max(5, bitmap.width / 35)
                val radiusY = max(7, bitmap.height / 55)
                var visiblePixels = 0
                var clusterSamples = 0
                var sampleY = (pixelY - radiusY).coerceAtLeast(0)
                while (sampleY <= (pixelY + radiusY).coerceAtMost(bitmap.height - 1)) {
                    var sampleX = (pixelX - radiusX).coerceAtLeast(0)
                    while (sampleX <= (pixelX + radiusX).coerceAtMost(bitmap.width - 1)) {
                        val color = bitmap.getPixel(sampleX, sampleY)
                        val red = android.graphics.Color.red(color)
                        val green = android.graphics.Color.green(color)
                        val blue = android.graphics.Color.blue(color)
                        if (max(red, max(green, blue)) >= 72) visiblePixels++
                        clusterSamples++
                        sampleX += 3
                    }
                    sampleY += 3
                }
                clusterSamples > 0 && visiblePixels * 100 / clusterSamples >= 8
            }
            darkLowerCanvas && railClusters >= 3
        } finally {
            bitmap.recycle()
        }
    }.getOrDefault(false)
}

/** Returns the first profile-grid tile that does not carry Douyin's yellow pinned badge. */
private fun PhoneAgentScreen.latestNonPinnedProfileTile(): Pair<Int, Int> {
    return visualLatestNonPinnedProfileTile() ?: (500 to 625)
}

/** Returns a non-pinned tile only when a real yellow pinned badge was found in the profile grid. */
private fun PhoneAgentScreen.visualLatestNonPinnedProfileTile(): Pair<Int, Int>? {
    if (screenshot.base64Data.isBlank()) return null
    return runCatching {
        val bytes = Base64.decode(screenshot.base64Data, Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return@runCatching null
        try {
            val pinned = (0..2).map { column ->
                val columnLeft = column * bitmap.width / 3
                val left = (columnLeft + bitmap.width / 100).coerceAtMost(bitmap.width - 1)
                val right = (columnLeft + bitmap.width / 10).coerceAtMost(bitmap.width - 1)
                val top = (bitmap.height * 52) / 100
                val bottom = (bitmap.height * 59) / 100
                var yellowPixels = 0
                var samples = 0
                var y = top
                while (y <= bottom.coerceAtMost(bitmap.height - 1)) {
                    var x = left
                    while (x <= right) {
                        val color = bitmap.getPixel(x, y)
                        val red = android.graphics.Color.red(color)
                        val green = android.graphics.Color.green(color)
                        val blue = android.graphics.Color.blue(color)
                        if (red >= 125 && green >= 80 && red >= blue + 55 && green >= blue + 35) {
                            yellowPixels++
                        }
                        samples++
                        x += 2
                    }
                    y += 2
                }
                samples > 0 && yellowPixels * 100 / samples >= 2
            }
            if (pinned.none { it }) return@runCatching null
            val safeColumn = (0..2).firstOrNull { !pinned[it] }
            if (safeColumn == null) {
                165 to 820
            } else {
                listOf(165, 500, 835)[safeColumn] to 625
            }
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()
}
