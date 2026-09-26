package com.reverfyx.brawlai.bot

import com.reverfyx.brawlai.vision.Detection
import kotlin.math.hypot

class PylaBotEngine {

    data class Action(
        val moveX: Float = 0f,
        val moveY: Float = 0f,
        val attack: Boolean = false,
        val useSuper: Boolean = false,
        val useGadget: Boolean = false,
        val debug: String = ""
    )

    fun decide(
        entities: List<Detection>,
        tiles: List<Detection>,
        frameWidth: Int,
        frameHeight: Int
    ): Action {
        val players = entities.filter { it.className == "player" }
        val enemies = entities.filter { it.className == "enemy" }
        val teammates = entities.filter { it.className == "teammate" }
        val player = players.maxByOrNull { it.confidence }
            ?: return Action(debug = "player not found")

        val px = player.centerX
        val py = player.centerY
        val minDim = minOf(frameWidth, frameHeight).toFloat()
        val safeRange = minDim * 0.30f
        val attackRange = minDim * 0.58f

        val nearestEnemy = enemies.minByOrNull {
            hypot((it.centerX - px).toDouble(), (it.centerY - py).toDouble())
        }

        var vx: Float
        var vy: Float
        var attack = false
        var debug: String

        if (nearestEnemy != null) {
            val dx = nearestEnemy.centerX - px
            val dy = nearestEnemy.centerY - py
            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            attack = dist <= attackRange

            if (dist > safeRange) {
                vx = dx
                vy = dy
                debug = "enemy ${dist.toInt()} -> approach${if (attack) " + attack" else ""}"
            } else {
                vx = -dx
                vy = -dy
                debug = "enemy ${dist.toInt()} -> kite + attack"
            }
        } else {
            val teammate = teammates.minByOrNull {
                hypot((it.centerX - px).toDouble(), (it.centerY - py).toDouble())
            }
            if (teammate != null) {
                val dx = teammate.centerX - px
                val dy = teammate.centerY - py
                val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (dist > minDim * 0.18f) {
                    vx = dx
                    vy = dy
                    debug = "follow teammate"
                } else {
                    vx = frameWidth / 2f - px
                    vy = frameHeight / 2f - py
                    debug = "teammate close -> center"
                }
            } else {
                vx = frameWidth / 2f - px
                vy = frameHeight / 2f - py
                debug = "no enemy -> center"
            }
        }

        val normalized = normalize(vx, vy)
        vx = normalized.first
        vy = normalized.second

        if (isBlocked(px, py, vx, vy, tiles, minDim * 0.26f)) {
            val left = normalize(-vy, vx)
            val right = normalize(vy, -vx)
            val leftBlocked = isBlocked(px, py, left.first, left.second, tiles, minDim * 0.23f)
            val rightBlocked = isBlocked(px, py, right.first, right.second, tiles, minDim * 0.23f)
            when {
                !leftBlocked -> { vx = left.first; vy = left.second; debug += " / avoid-left" }
                !rightBlocked -> { vx = right.first; vy = right.second; debug += " / avoid-right" }
                else -> { vx = -vx; vy = -vy; debug += " / reverse" }
            }
        }

        return Action(moveX=vx, moveY=vy, attack=attack, debug=debug)
    }

    private fun normalize(x: Float, y: Float): Pair<Float, Float> {
        val m = hypot(x.toDouble(), y.toDouble()).toFloat()
        if (m < 0.001f) return 0f to 0f
        return x / m to y / m
    }

    private fun isBlocked(px:Float,py:Float,vx:Float,vy:Float,tiles:List<Detection>,lookAhead:Float):Boolean {
        if (tiles.isEmpty()) return false
        val tx=px+vx*lookAhead
        val ty=py+vy*lookAhead
        val corridor=lookAhead*0.35f
        return tiles.any { d ->
            if (d.className != "wall" && d.className != "close_bush") return@any false
            distanceToSegment(d.centerX,d.centerY,px,py,tx,ty) < corridor
        }
    }

    private fun distanceToSegment(x:Float,y:Float,x1:Float,y1:Float,x2:Float,y2:Float):Float {
        val dx=x2-x1; val dy=y2-y1; val lenSq=dx*dx+dy*dy
        if (lenSq <= 0.0001f) return hypot((x-x1).toDouble(),(y-y1).toDouble()).toFloat()
        val t=(((x-x1)*dx+(y-y1)*dy)/lenSq).coerceIn(0f,1f)
        val qx=x1+t*dx; val qy=y1+t*dy
        return hypot((x-qx).toDouble(),(y-qy).toDouble()).toFloat()
    }
}
