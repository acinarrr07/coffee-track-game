package com.coffeetrack.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

private class Meal(val name: String, val emoji: String)

private class Popup(val text: String, val x: Float, var y: Float, val color: Int) {
    var life = 1.3f
}

private val SKINS = listOf("#F5CBA7", "#E0AC69", "#C68642", "#8D5524", "#FFDBAC").map { Color.parseColor(it) }
private val HAIRS = listOf("#2C1810", "#5D4037", "#A0522D", "#D4A017", "#B71C1C", "#37474F").map { Color.parseColor(it) }
private val SHIRTS = listOf("#3F7CAC", "#E07A5F", "#81B29A", "#9B5DE5", "#F2CC8F", "#EF476F", "#06A77D").map { Color.parseColor(it) }

/** A guest sitting at a table. Each guest has a random look, an order and a patience timer. */
private class Customer(val meal: Meal, val maxPatience: Float, val vip: Boolean) {
    var patience = maxPatience
    var exit = 0        // 0 = waiting, 1 = served (happy), 2 = left angry
    var t = 0f          // seconds since arrival
    var exitT = 0f      // seconds since leaving
    val skin = SKINS.random()
    val hair = HAIRS.random()
    val shirt = SHIRTS.random()
    val style = Random.nextInt(4)
    val glasses = Random.nextInt(4) == 0
}

class GameView(ctx: Context) : View(ctx) {

    private enum class State { MENU, PLAY, OVER }

    // ---------- MENU: 12 different meals, unlocked level by level ----------
    private val meals = listOf(
        Meal("Espresso", "☕"), Meal("Croissant", "🥐"), Meal("Tea", "🍵"),
        Meal("Sandwich", "🥪"), Meal("Cake", "🍰"), Meal("Donut", "🍩"),
        Meal("Pancakes", "🥞"), Meal("Bagel", "🥯"), Meal("Cookie", "🍪"),
        Meal("Cupcake", "🧁"), Meal("Salad", "🥗"), Meal("Ice Cream", "🍨")
    )

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val rf = RectF()
    private val path = Path()
    private val btn = RectF()
    private val prefs = ctx.getSharedPreferences("coffee_track", Context.MODE_PRIVATE)
    private lateinit var cv: Canvas

    private var W = 0f
    private var H = 0f
    private var topInset = 0f
    private var bottomInset = 0f

    private var state = State.MENU
    private var score = 0
    private var lives = 3
    private var level = 1
    private var combo = 0
    private var served = 0
    private var best = prefs.getInt("best", 0)
    private var newBest = false
    private var selected = -1
    private var spawnTimer = 1f
    private var banner = ""
    private var bannerSub = ""
    private var bannerT = 0f
    private var lastT = System.nanoTime()

    private val seats = arrayOfNulls<Customer>(4)
    private val popups = mutableListOf<Popup>()

    init {
        setOnApplyWindowInsetsListener { _, ins ->
            topInset = ins.systemWindowInsetTop.toFloat()
            bottomInset = ins.systemWindowInsetBottom.toFloat()
            ins
        }
    }

    // =====================================================================
    //  GAME LOGIC
    // =====================================================================

    private fun startGame() {
        for (i in seats.indices) seats[i] = null
        popups.clear()
        score = 0; lives = 3; level = 1; combo = 0; served = 0
        selected = -1; newBest = false; spawnTimer = 0.4f; bannerT = 0f
        state = State.PLAY
    }

    private fun gameOver() {
        state = State.OVER
        newBest = score > best
        if (newBest) {
            best = score
            prefs.edit().putInt("best", best).apply()
        }
    }

    private fun unlockedCount() = min(meals.size, 4 + 2 * level)

    private fun unlockLevel(idx: Int) = if (idx < 6) 1 else (idx - 6) / 2 + 2

    private fun spawn() {
        val free = seats.indices.filter { seats[it] == null }
        if (free.isEmpty()) return
        val vip = Random.nextInt(100) < 12
        val patience = max(6f, 15f - 1.2f * level) * (if (vip) 0.8f else 1f)
        seats[free.random()] = Customer(meals[Random.nextInt(unlockedCount())], patience, vip)
    }

    private fun pop(text: String, x: Float, color: Int) {
        popups.add(Popup(text, x, H * 0.50f, color))
    }

    private fun addScore(pts: Int) {
        score += pts
        while (score >= level * 100) {
            level++
            score += 20
            lives = min(5, lives + 1)
            banner = "LEVEL $level!"
            bannerSub = if (level <= 4) "+20 bonus   +1 life   New meals!" else "+20 bonus   +1 life   Faster guests!"
            bannerT = 2.5f
        }
    }

    private fun serve(i: Int, c: Customer) {
        val cx = W * (i + 0.5f) / 4f
        if (c.meal === meals[selected]) {
            combo++
            val speedBonus = (c.patience / c.maxPatience * 10f).toInt()   // up to +10
            val comboBonus = min(combo - 1, 5) * 2                         // up to +10
            var pts = 10 + speedBonus + comboBonus
            if (c.vip) pts *= 2
            c.exit = 1
            served++
            pop("+$pts", cx, col("#7CFC00"))
            addScore(pts)
        } else {
            combo = 0
            score = max(0, score - 5)
            c.patience -= c.maxPatience * 0.2f
            pop("-5 Wrong meal!", cx, col("#FF5252"))
        }
    }

    private fun loseLife(i: Int) {
        lives--
        combo = 0
        pop("-1 ❤", W * (i + 0.5f) / 4f, col("#FF5252"))
        if (lives <= 0) gameOver()
    }

    private fun update(dt: Float) {
        if (bannerT > 0f) bannerT -= dt
        for (po in popups) { po.life -= dt; po.y -= H * 0.08f * dt }
        popups.removeAll { it.life <= 0f }
        if (state != State.PLAY) return

        spawnTimer -= dt
        if (spawnTimer <= 0f) {
            spawn()
            spawnTimer = max(1.0f, 3.4f - 0.35f * level) + Random.nextFloat()
        }
        for (i in seats.indices) {
            val c = seats[i] ?: continue
            c.t += dt
            if (c.exit == 0) {
                c.patience -= dt
                if (c.patience <= 0f) {
                    c.exit = 2
                    loseLife(i)
                    if (state != State.PLAY) return
                }
            } else {
                c.exitT += dt
                if (c.exitT > 0.9f) seats[i] = null
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_DOWN) return true
        val x = e.x
        val y = e.y - topInset
        when (state) {
            State.MENU, State.OVER -> if (btn.contains(x, y)) startGame()
            State.PLAY -> {
                val gridTop = 0.755f * H
                val cellH = (0.985f * H - gridTop) / 3f
                if (y > gridTop) {
                    val column = (x / (W / 4f)).toInt().coerceIn(0, 3)
                    val row = ((y - gridTop) / cellH).toInt()
                    val idx = row * 4 + column
                    if (row in 0..2 && idx < unlockedCount()) selected = idx
                } else if (y > 0.34f * H && y < 0.70f * H) {
                    val seat = (x / (W / 4f)).toInt().coerceIn(0, 3)
                    val c = seats[seat]
                    if (c != null && c.exit == 0) {
                        if (selected >= 0) serve(seat, c)
                        else pop("Pick a meal first!", W * (seat + 0.5f) / 4f, col("#FFD166"))
                    }
                }
            }
        }
        return true
    }

    // =====================================================================
    //  DRAWING HELPERS
    // =====================================================================

    private fun col(s: String) = Color.parseColor(s)
    private fun fill(color: Int) { p.style = Paint.Style.FILL; p.color = color }
    private fun stroke(color: Int, w: Float) { p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = w }
    private fun rect(l: Float, t: Float, r: Float, b: Float, color: Int) { fill(color); cv.drawRect(l, t, r, b, p) }
    private fun rrect(l: Float, t: Float, r: Float, b: Float, rad: Float, color: Int) {
        fill(color); rf.set(l, t, r, b); cv.drawRoundRect(rf, rad, rad, p)
    }
    private fun circle(x: Float, y: Float, r: Float, color: Int) { fill(color); cv.drawCircle(x, y, r, p) }
    private fun text(s: String, x: Float, y: Float, size: Float, color: Int = Color.WHITE,
                     align: Paint.Align = Paint.Align.CENTER, alpha: Int = 255) {
        tp.textSize = size; tp.color = color; tp.alpha = alpha; tp.textAlign = align
        cv.drawText(s, x, y, tp)
    }
    private fun emoji(s: String, x: Float, y: Float, size: Float) {
        text(s, x, y + size * 0.35f, size)
    }

    // =====================================================================
    //  DRAWING
    // =====================================================================

    override fun onDraw(canvas: Canvas) {
        cv = canvas
        val now = System.nanoTime()
        val dt = min(0.05f, (now - lastT) / 1e9f)
        lastT = now
        W = width.toFloat()
        H = height - topInset - bottomInset

        canvas.drawColor(col("#3E2723"))
        canvas.save()
        canvas.translate(0f, topInset)

        update(dt)
        drawScene()
        if (state != State.MENU) drawCustomers()
        drawTables()
        drawTray()
        drawHud()
        drawPopups()
        drawBanner()
        if (state == State.MENU) drawMenu()
        if (state == State.OVER) drawOver()

        canvas.restore()
        postInvalidateOnAnimation()
    }

    /** Café ambience: wallpaper, window, lamps, sign, plants, counter, barista, checkered floor. */
    private fun drawScene() {
        // wall + wallpaper stripes + wooden panel
        rect(0f, .075f * H, W, .30f * H, col("#F3DDB9"))
        for (i in 0 until 10 step 2) rect(W * i / 10f, .075f * H, W * (i + 1) / 10f, .22f * H, col("#EAD0A5"))
        rect(0f, .22f * H, W, .30f * H, col("#8D5A3B"))
        rect(0f, .22f * H, W, .226f * H, col("#6D4228"))
        rect(0f, .075f * H, W, .082f * H, col("#5D3A24"))

        // window with morning sun
        rrect(.05f * W, .10f * H, .29f * W, .205f * H, W * .02f, col("#6D4228"))
        rect(.065f * W, .113f * H, .275f * W, .155f * H, col("#FFE9A8"))
        rect(.065f * W, .155f * H, .275f * W, .192f * H, col("#FFC48A"))
        circle(.11f * W, .135f * H, .018f * H, col("#FFB300"))
        rect(.164f * W, .113f * H, .176f * W, .192f * H, col("#6D4228"))
        rect(.065f * W, .150f * H, .275f * W, .157f * H, col("#6D4228"))

        // hanging lamps with warm glow
        for (lx in listOf(.31f, .84f)) {
            stroke(col("#3E2723"), W * .004f)
            cv.drawLine(W * lx, .082f * H, W * lx, .098f * H, p)
            fill(col("#33FFE08A"))
            cv.drawCircle(W * lx, .122f * H, W * .08f, p)
            fill(col("#E0A030"))
            rf.set(W * lx - W * .04f, .098f * H, W * lx + W * .04f, .126f * H)
            cv.drawArc(rf, 180f, 180f, true, p)
        }

        // sign board
        rrect(.36f * W, .108f * H, .76f * W, .17f * H, W * .03f, col("#2E4A3A"))
        stroke(col("#C8A165"), W * .006f)
        rf.set(.36f * W, .108f * H, .76f * W, .17f * H)
        cv.drawRoundRect(rf, W * .03f, W * .03f, p)
        text("COFFEE TRACK", .56f * W, .147f * H, W * .044f, col("#F5E6D3"))

        // shelf with plants
        rrect(.80f * W, .205f * H, .98f * W, .213f * H, W * .01f, col("#6D4228"))
        emoji("🌿", .86f * W, .185f * H, .04f * H)
        emoji("🌵", .93f * W, .185f * H, .04f * H)

        // barista behind the counter
        val bx = .58f * W
        val br = .03f * H
        drawPerson(bx, .228f * H, br, SKINS[1], 3, col("#B03A2E"), col("#6D4C41"), 2, false)
        rrect(bx - br * .8f, .228f * H + br * 1.0f, bx + br * .8f, .228f * H + br * 3.1f, br * .3f, Color.WHITE)

        // counter + things on it
        rect(0f, .30f * H, W, .315f * H, col("#C9955C"))
        rect(0f, .315f * H, W, .345f * H, col("#7A4B2A"))
        emoji("🍰", .10f * W, .288f * H, .04f * H)
        emoji("☕", .28f * W, .288f * H, .04f * H)
        emoji("🥐", .86f * W, .288f * H, .04f * H)

        // checkered floor
        val tile = W / 8f
        var yy = .345f * H
        var row = 0
        while (yy < .70f * H) {
            for (ci in 0 until 8) {
                fill(if ((row + ci) % 2 == 0) col("#EAD7B7") else col("#D2B48C"))
                cv.drawRect(ci * tile, yy, (ci + 1) * tile, min(yy + tile, .70f * H), p)
            }
            yy += tile
            row++
        }
        rect(0f, .345f * H, W, .36f * H, col("#33000000"))
    }

    /** Generic cartoon character used for guests and the barista. */
    private fun drawPerson(cx: Float, hy: Float, r: Float, skin: Int, style: Int, hair: Int,
                           shirt: Int, mood: Int, glasses: Boolean) {
        val dark = col("#3E2723")
        if (style == 1) rrect(cx - r * 1.1f, hy - r, cx + r * 1.1f, hy + r * 1.9f, r * .6f, hair)
        rrect(cx - r * 1.25f, hy + r * .85f, cx + r * 1.25f, hy + r * 3.2f, r * .5f, shirt)
        circle(cx, hy, r, skin)
        if (mood == 0) circle(cx, hy, r, col("#55FF0000"))

        fill(hair)
        rf.set(cx - r * 1.04f, hy - r * 1.08f, cx + r * 1.04f, hy + r * .35f)
        cv.drawArc(rf, 180f, 180f, true, p)
        if (style == 2) circle(cx, hy - r * 1.15f, r * .38f, hair)
        if (style == 3) rrect(cx - r * 1.15f, hy - r * .45f, cx + r * .7f, hy - r * .3f, r * .1f, hair)

        // eyes
        circle(cx - r * .38f, hy + r * .12f, r * .1f, dark)
        circle(cx + r * .38f, hy + r * .12f, r * .1f, dark)
        if (glasses) {
            stroke(dark, r * .06f)
            cv.drawCircle(cx - r * .38f, hy + r * .12f, r * .22f, p)
            cv.drawCircle(cx + r * .38f, hy + r * .12f, r * .22f, p)
            cv.drawLine(cx - r * .16f, hy + r * .12f, cx + r * .16f, hy + r * .12f, p)
        }
        stroke(col("#7B241C"), r * .1f)
        when (mood) {
            2 -> { rf.set(cx - r * .4f, hy + r * .1f, cx + r * .4f, hy + r * .65f); cv.drawArc(rf, 20f, 140f, false, p) }
            1 -> cv.drawLine(cx - r * .25f, hy + r * .5f, cx + r * .25f, hy + r * .5f, p)
            else -> {
                rf.set(cx - r * .35f, hy + r * .45f, cx + r * .35f, hy + r * .85f); cv.drawArc(rf, 200f, 140f, false, p)
                stroke(dark, r * .12f)
                cv.drawLine(cx - r * .6f, hy - r * .15f, cx - r * .2f, hy + r * .02f, p)
                cv.drawLine(cx + r * .6f, hy - r * .15f, cx + r * .2f, hy + r * .02f, p)
            }
        }
    }

    private fun drawCustomers() {
        for (i in seats.indices) seats[i]?.let { drawCustomer(i, it) }
    }

    private fun drawCustomer(i: Int, c: Customer) {
        val cx = W * (i + .5f) / 4f
        val r = min(W * .065f, H * .045f)
        val hy = .535f * H
        val slide = (1f - min(1f, c.t / .35f)) * H * .08f
        val ratio = c.patience / c.maxPatience
        val mood = when {
            c.exit == 1 -> 2
            c.exit == 2 -> 0
            ratio > .5f -> 2
            ratio > .25f -> 1
            else -> 0
        }
        cv.save()
        cv.translate(0f, slide)
        drawPerson(cx, hy, r, c.skin, c.style, c.hair, c.shirt, mood, c.glasses)
        if (c.vip) emoji("👑", cx, hy - r * 1.45f, r * 1.1f)

        // speech bubble with the order
        val bw = W * .095f
        val top = .37f * H
        val h = bw * 1.9f
        val bot = top + h
        val bg = col(if (c.vip) "#FFE08A" else "#FFFFFF")
        val border = col(if (c.vip) "#C9A227" else "#8D6E63")
        path.reset()
        path.moveTo(cx - bw * .2f, bot - 2f); path.lineTo(cx + bw * .2f, bot - 2f); path.lineTo(cx, bot + bw * .3f); path.close()
        fill(bg); cv.drawPath(path, p)
        rrect(cx - bw, top, cx + bw, bot, bw * .3f, bg)
        stroke(border, W * .006f)
        rf.set(cx - bw, top, cx + bw, bot)
        cv.drawRoundRect(rf, bw * .3f, bw * .3f, p)

        when (c.exit) {
            0 -> {
                emoji(c.meal.emoji, cx, top + h * .40f, bw * .95f)
                text(c.meal.name, cx, bot - h * .10f, bw * .30f, col("#5D4037"))
                // patience bar
                rrect(cx - bw, top - .016f * H, cx + bw, top - .006f * H, 8f, col("#66000000"))
                val barColor = when { ratio > .5f -> "#4CAF50"; ratio > .25f -> "#FF9800"; else -> "#F44336" }
                rrect(cx - bw, top - .016f * H, cx - bw + 2f * bw * ratio.coerceIn(0f, 1f), top - .006f * H, 8f, col(barColor))
            }
            1 -> emoji("❤️", cx, top + h * .5f, bw * 1.1f)
            else -> emoji("💢", cx, top + h * .5f, bw * 1.1f)
        }
        cv.restore()
    }

    private fun drawTables() {
        for (i in 0 until 4) {
            val cx = W * (i + .5f) / 4f
            rect(cx - .012f * W, .665f * H, cx + .012f * W, .70f * H, col("#5D4037"))
            rf.set(cx - .06f * W, .692f * H, cx + .06f * W, .708f * H)
            fill(col("#4E342E")); cv.drawOval(rf, p)
            rf.set(cx - .105f * W, .615f * H, cx + .105f * W, .665f * H)
            fill(col("#C0392B")); cv.drawOval(rf, p)
            rf.set(cx - .085f * W, .622f * H, cx + .085f * W, .652f * H)
            fill(col("#E74C3C")); cv.drawOval(rf, p)
            val c = seats[i]
            if (c != null && c.exit == 1) emoji(c.meal.emoji, cx, .633f * H, .045f * H)
        }
    }

    private fun drawTray() {
        val top = .72f * H
        rrect(0f, top, W, H + bottomInset + 40f, W * .05f, col("#4E342E"))
        text("Tap a meal, then tap the customer", W / 2f, .742f * H, W * .036f, col("#F5E6D3"))
        val gridTop = .755f * H
        val cw = W / 4f
        val ch = (.985f * H - gridTop) / 3f
        val pad = W * .012f
        val unlocked = unlockedCount()
        for (idx in meals.indices) {
            val l = (idx % 4) * cw + pad
            val t = gridTop + (idx / 4) * ch + pad
            val r = l + cw - 2 * pad
            val b = t + ch - 2 * pad
            val cx = (l + r) / 2f
            if (idx < unlocked) {
                rrect(l, t, r, b, W * .03f, col(if (idx == selected) "#FFD166" else "#F5E6D3"))
                emoji(meals[idx].emoji, cx, t + ch * .38f, ch * .46f)
                text(meals[idx].name, cx, b - ch * .09f, ch * .19f, col("#5D4037"))
                if (idx == selected) {
                    stroke(col("#FF6F00"), W * .01f)
                    rf.set(l, t, r, b); cv.drawRoundRect(rf, W * .03f, W * .03f, p)
                }
            } else {
                rrect(l, t, r, b, W * .03f, col("#3E2723"))
                emoji("🔒", cx, t + ch * .36f, ch * .36f)
                text("Level ${unlockLevel(idx)}", cx, b - ch * .09f, ch * .19f, col("#A1887F"))
            }
        }
    }

    private fun drawHud() {
        rect(0f, 0f, W, .075f * H, col("#3E2723"))
        text("Score $score", W * .04f, .036f * H, W * .05f, Color.WHITE, Paint.Align.LEFT)
        text("Best $best", W * .04f, .066f * H, W * .032f, col("#BCAAA4"), Paint.Align.LEFT)
        text("❤️".repeat(max(0, lives)), W / 2f, .036f * H, W * .045f)
        if (combo >= 2) {
            text("🔥 Combo x$combo (+${min(combo - 1, 5) * 2})", W / 2f, .066f * H, W * .033f, col("#FFB74D"))
        }
        text("Lv $level", W * .96f, .036f * H, W * .05f, col("#FFD166"), Paint.Align.RIGHT)
        text("Served $served", W * .96f, .066f * H, W * .032f, col("#BCAAA4"), Paint.Align.RIGHT)
    }

    private fun drawPopups() {
        for (po in popups) {
            val a = (min(1f, po.life) * 255).toInt()
            text(po.text, po.x.coerceIn(W * .2f, W * .8f), po.y, W * .05f, po.color, Paint.Align.CENTER, a)
        }
    }

    private fun drawBanner() {
        if (bannerT <= 0f) return
        rrect(.08f * W, .085f * H, .92f * W, .20f * H, W * .04f, col("#E6C86A"))
        text(banner, W / 2f, .13f * H, W * .075f, col("#3E2723"))
        text(bannerSub, W / 2f, .178f * H, W * .036f, col("#5D4037"))
    }

    private fun drawButton(label: String, l: Float, t: Float, r: Float, b: Float) {
        btn.set(l, t, r, b)
        rrect(l, t, r, b, W * .04f, col("#C8864B"))
        text(label, (l + r) / 2f, (t + b) / 2f + W * .06f * .35f, W * .06f, col("#2B1A12"))
    }

    private fun drawMenu() {
        rect(0f, 0f, W, H, col("#B0000000"))
        val y0 = .12f * H
        rrect(.06f * W, y0, .94f * W, .88f * H, W * .05f, col("#3E2723"))
        text("☕ COFFEE TRACK", W / 2f, y0 + .07f * H, W * .085f, col("#FFD166"))
        text("Serve every guest the meal they ordered", W / 2f, y0 + .12f * H, W * .036f, col("#F5E6D3"))
        text("before their patience runs out!", W / 2f, y0 + .15f * H, W * .036f, col("#F5E6D3"))
        text("HOW TO SCORE", W / 2f, y0 + .215f * H, W * .045f, col("#FFD166"))
        val rules = listOf(
            "✅ Correct meal: +10 points",
            "⚡ Speed bonus: up to +10",
            "🔥 Combo streak: +2 each (max +10)",
            "👑 VIP guest: double points",
            "❌ Wrong meal: -5 points",
            "💔 Guest leaves angry: -1 life",
            "⬆️ Every 100 pts: level up +20"
        )
        for ((i, s) in rules.withIndex()) {
            text(s, W * .12f, y0 + (.27f + i * .05f) * H, W * .037f, col("#F5E6D3"), Paint.Align.LEFT)
        }
        text("Best score: $best", W / 2f, y0 + .64f * H, W * .045f, col("#BCAAA4"))
        drawButton("PLAY", W * .25f, y0 + .665f * H, W * .75f, y0 + .735f * H)
    }

    private fun drawOver() {
        rect(0f, 0f, W, H, col("#B0000000"))
        rrect(.08f * W, .25f * H, .92f * W, .75f * H, W * .05f, col("#3E2723"))
        text("GAME OVER", W / 2f, .32f * H, W * .09f, col("#FF7043"))
        text("Score: $score", W / 2f, .40f * H, W * .06f, Color.WHITE)
        text(if (newBest) "🏆 New best score!" else "Best: $best", W / 2f, .46f * H, W * .045f, col("#FFD166"))
        text("Level reached: $level", W / 2f, .53f * H, W * .045f, col("#F5E6D3"))
        text("Guests served: $served", W / 2f, .59f * H, W * .045f, col("#F5E6D3"))
        drawButton("PLAY AGAIN", W * .2f, .64f * H, W * .8f, .71f * H)
    }
}
