package io.github.opentouchpad

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 动作选择弹窗：与设置页保持同一套 Material 3 / 暖色卡片视觉体系（24dp 大圆角卡片、衬线标题、
 * 当前动作胶囊标签、14dp 圆角选项行与胶囊取消按钮，并完整跟随浅/深色与 Material You 动态配色）。
 * 设置页与悬浮面板长按共用。
 */
internal object ActionPicker {
    fun build(
        context: Context,
        dark: Boolean,
        current: PadAction,
        actions: List<PadAction> = PadAction.ALL,
        titleRes: Int = R.string.pick_action,
        noneLabelRes: Int = R.string.act_none,
        accentColor: Int? = null,
        allowOverlayAbove: Boolean = false,
        onPick: (PadAction) -> Unit
    ): AlertDialog {
        val themed = android.view.ContextThemeWrapper(context, if (dark) R.style.WarmDialog_Dark else R.style.WarmDialog)
        val dm = context.resources.displayMetrics
        val density = dm.density
        fun dp(v: Int) = (v * density).roundToInt()

        val prefs = Prefs(context)
        val baseTheme = resolveSettingsTheme(context, dark, prefs.dynamicColor, prefs.uiAccentColor)
        val accent = accentColor?.takeIf { it != 0 } ?: baseTheme.accentColor
        val (accentSoft, accentSoftStrong) = if (accent == baseTheme.accentColor) {
            baseTheme.accentSoftColor to baseTheme.accentSoftStrongColor
        } else {
            resolveUiAccentSoftColors(accent, dark)
        }

        val surfaceColor = baseTheme.surfaceColor
        val elevatedColor = baseTheme.elevatedColor
        val trackColor = baseTheme.trackColor
        val borderColor = baseTheme.borderColor
        val textColor = baseTheme.textColor
        val secondaryTextColor = baseTheme.secondaryTextColor
        val rippleColor = baseTheme.rippleColor

        val serif = runCatching { context.resources.getFont(R.font.serif_medium) }.getOrDefault(Typeface.SERIF)
        val sansMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        val effectiveActions = if (current in actions) actions else listOf(current) + actions
        val accentTint = ColorStateList.valueOf(accent)
        val textTint = ColorStateList.valueOf(textColor)
        val rippleCsl = ColorStateList.valueOf(rippleColor)
        val strokePx = maxOf(1, dp(1))
        val dividerPx = maxOf(1, dp(1) / 2)

        fun roundedRect(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeWidth: Int = 0): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(radiusDp).toFloat()
                setColor(color)
                if (strokeWidth > 0 && strokeColor != 0) {
                    setStroke(strokeWidth, strokeColor)
                }
            }

        fun actionLabel(action: PadAction): String = if (action == PadAction.NONE) {
            context.getString(noneLabelRes)
        } else {
            context.getString(action.labelRes)
        }

        lateinit var dialog: AlertDialog

        class RowHolder(
            val card: LinearLayout,
            val cardBg: GradientDrawable,
            val icon: ImageView,
            val iconBg: GradientDrawable,
            val label: TextView,
            val check: ImageView,
            var action: PadAction = current,
        )

        val adapter = object : BaseAdapter() {
            override fun getCount() = effectiveActions.size
            override fun getItem(position: Int) = effectiveActions[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val wrapper: FrameLayout
                val holder: RowHolder
                if (convertView is FrameLayout && convertView.tag is RowHolder) {
                    wrapper = convertView
                    holder = convertView.tag as RowHolder
                } else {
                    val cardBg = roundedRect(Color.TRANSPARENT, 14)
                    val mask = roundedRect(Color.WHITE, 14)
                    val iconBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
                    val icon = ImageView(themed).apply {
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        background = iconBg
                    }
                    val label = TextView(themed).apply {
                        textSize = 15f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                    val check = ImageView(themed).apply {
                        setImageResource(R.drawable.ic_lu_check)
                        imageTintList = accentTint
                    }
                    val card = LinearLayout(themed).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(52)
                        setPadding(dp(12), dp(7), dp(14), dp(7))
                        background = RippleDrawable(rippleCsl, cardBg, mask)
                        isClickable = true
                        addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)))
                        addView(
                            label,
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                marginStart = dp(12)
                                marginEnd = dp(8)
                            },
                        )
                        addView(check, LinearLayout.LayoutParams(dp(18), dp(18)))
                    }
                    holder = RowHolder(card, cardBg, icon, iconBg, label, check)
                    card.setOnClickListener {
                        onPick(holder.action)
                        dialog.dismiss()
                    }
                    wrapper = FrameLayout(themed).apply {
                        setPadding(dp(10), dp(2), dp(10), dp(2))
                        addView(
                            card,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ),
                        )
                        tag = holder
                    }
                }

                val action = effectiveActions[position]
                val selected = action == current
                holder.action = action
                holder.cardBg.setColor(if (selected) accentSoft else Color.TRANSPARENT)
                holder.cardBg.setStroke(if (selected) strokePx else 0, if (selected) accentSoftStrong else 0)
                holder.icon.setImageResource(action.iconRes)
                holder.icon.imageTintList = if (selected) accentTint else textTint
                holder.iconBg.setColor(if (selected) elevatedColor else trackColor)
                holder.iconBg.setStroke(if (selected) strokePx else 0, if (selected) accentSoftStrong else 0)

                val labelText = actionLabel(action)
                holder.label.text = labelText
                holder.label.typeface = if (selected) sansMedium else Typeface.DEFAULT
                holder.label.setTextColor(if (selected) accent else textColor)
                holder.check.visibility = if (selected) View.VISIBLE else View.GONE
                holder.card.contentDescription = labelText
                return wrapper
            }
        }

        // ── 顶部标题栏（当前动作图标徽章 + 衬线标题 + 当前动作状态胶囊）──
        val headerIcon = ImageView(themed).apply {
            setImageResource(current.iconRes)
            imageTintList = accentTint
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accentSoft)
                setStroke(strokePx, accentSoftStrong)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val titleView = TextView(themed).apply {
            text = context.getString(titleRes)
            textSize = 19f
            typeface = serif
            setTextColor(textColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val currentChip = TextView(themed).apply {
            text = actionLabel(current)
            textSize = 12f
            typeface = sansMedium
            setTextColor(secondaryTextColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(116)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = roundedRect(trackColor, 999)
        }
        val header = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            addView(headerIcon, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(
                titleView,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(12)
                    marginEnd = dp(8)
                },
            )
            addView(currentChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun makeDivider(): View = View(themed).apply {
            setBackgroundColor(borderColor)
        }

        val maxListHeightPx = computeActionPickerMaxListHeight(dm.heightPixels, density)
        val listView = object : ListView(themed) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val mode = MeasureSpec.getMode(heightMeasureSpec)
                val size = MeasureSpec.getSize(heightMeasureSpec)
                val limit = if (mode == MeasureSpec.UNSPECIFIED) maxListHeightPx else minOf(size, maxListHeightPx)
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit.coerceAtLeast(1), MeasureSpec.AT_MOST))
            }
        }.apply {
            this.adapter = adapter
            divider = null
            dividerHeight = 0
            selector = ColorDrawable(Color.TRANSPARENT)
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(8))
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
        }

        // ── 底部圆角取消按钮 ──
        val cancelBtn = TextView(themed).apply {
            text = context.getString(android.R.string.cancel)
            textSize = 14f
            typeface = sansMedium
            gravity = Gravity.CENTER
            setTextColor(textColor)
            val btnFace = roundedRect(trackColor, 14, borderColor, strokePx)
            val btnMask = roundedRect(Color.WHITE, 14)
            background = RippleDrawable(rippleCsl, btnFace, btnMask)
            isClickable = true
            setOnClickListener { dialog.cancel() }
        }
        val footer = FrameLayout(themed).apply {
            setPadding(dp(16), dp(12), dp(16), dp(14))
            addView(cancelBtn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        }

        val rootCard = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(surfaceColor, 24, borderColor, strokePx)
            clipToOutline = true
            addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(
                makeDivider(),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dividerPx).apply {
                    marginStart = dp(18)
                    marginEnd = dp(18)
                },
            )
            addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(
                makeDivider(),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dividerPx).apply {
                    marginStart = dp(18)
                    marginEnd = dp(18)
                },
            )
            addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        dialog = AlertDialog.Builder(themed)
            .setView(rootCard)
            .create()

        fun configureWindow(afterShow: Boolean) {
            dialog.window?.let { win ->
                win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                if (afterShow) {
                    // 仅在 show() 完成 AlertController.installContent() 之后再访问 decorView，
                    // 避免提前实例化 decorView 触发 requestFeature() 异常。
                    win.decorView.setPadding(0, 0, 0, 0)
                }
                val metrics = context.resources.displayMetrics
                val lp = win.attributes
                lp.width = computeActionPickerDialogWidth(metrics.widthPixels, density)
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                lp.dimAmount = if (dark) 0.56f else 0.38f
                if (allowOverlayAbove) {
                    // 清除 FLAG_WATCH_OUTSIDE_TOUCH：点击叠在弹窗上方的悬浮触控板/悬浮球时，
                    // 不再向弹窗派发 ACTION_OUTSIDE 导致误关闭；点击空白遮罩仍可正常关闭。
                    lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
                }
                win.attributes = lp
            }
        }

        configureWindow(afterShow = false)

        dialog.setOnShowListener {
            configureWindow(afterShow = true)
            val idx = effectiveActions.indexOf(current)
            if (idx >= 0) {
                listView.post {
                    listView.setSelectionFromTop(idx, dp(84))
                }
            }
        }
        return dialog
    }
}
