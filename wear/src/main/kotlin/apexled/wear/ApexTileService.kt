package apexled.wear

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Bloco (tile) do relógio: liga/desliga o modo seguro e as setas sem abrir o app.
 * Para adicionar: deslize a tela inicial do relógio até o fim → "+" → Apex LED.
 */
class ApexTileService : TileService() {

    override fun onTileRequest(req: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        when (req.currentState.lastClickableId) {
            "safety" -> {
                val on = !PhoneCommands.safety(this)
                PhoneCommands.send(this, PhoneLink.SAFETY, byteArrayOf(if (on) 1 else 0))
                PhoneCommands.saveState(this, on, PhoneCommands.turn(this))
            }
            "left" -> turn("LEFT", 1)
            "right" -> turn("RIGHT", 2)
            "hazard" -> turn("HAZARD", 3)
        }
        val safety = PhoneCommands.safety(this)
        val turn = PhoneCommands.turn(this)
        val root = LayoutElementBuilders.Column.Builder()
            .addContent(text("Apex LED", 13f, GREY))
            .addContent(space(6f))
            .addContent(button("safety", if (safety) "Seguro ON" else "Seguro OFF", if (safety) GREEN else CARD, 130f, 44f))
            .addContent(space(8f))
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .addContent(button("left", "◀", if (turn == "LEFT") AMBER else CARD, 52f, 44f))
                    .addContent(spaceW(6f))
                    .addContent(button("hazard", "⚠", if (turn == "HAZARD") AMBER else CARD, 44f, 44f))
                    .addContent(spaceW(6f))
                    .addContent(button("right", "▶", if (turn == "RIGHT") AMBER else CARD, 52f, 44f))
                    .build(),
            )
            .build()
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RES_VERSION)
            .setFreshnessIntervalMillis(0)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(req: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RES_VERSION).build())

    /** Mesmo lado de novo = desligar (como no celular). */
    private fun turn(name: String, code: Byte) {
        val off = PhoneCommands.turn(this) == name
        PhoneCommands.send(this, PhoneLink.TURN, byteArrayOf(if (off) 0 else code))
        PhoneCommands.saveState(this, PhoneCommands.safety(this), if (off) null else name)
    }

    private fun text(t: String, size: Float, color: Int) = LayoutElementBuilders.Text.Builder().setText(t)
        .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color)).build()).build()

    private fun space(h: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(h)).build()
    private fun spaceW(w: Float) = LayoutElementBuilders.Spacer.Builder().setWidth(dp(w)).build()

    private fun button(id: String, label: String, color: Int, w: Float, h: Float) = LayoutElementBuilders.Box.Builder()
        .setWidth(dp(w)).setHeight(dp(h))
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setClickable(ModifiersBuilders.Clickable.Builder().setId(id).setOnClick(ActionBuilders.LoadAction.Builder().build()).build())
                .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(color))
                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(h / 2)).build()).build())
                .build(),
        )
        .addContent(text(label, 15f, WHITE))
        .build()

    companion object {
        private const val RES_VERSION = "1"
        private const val GREEN = 0xFF22C55E.toInt()
        private const val AMBER = 0xFFFF8C00.toInt()
        private const val CARD = 0xFF2A2E35.toInt()
        private const val GREY = 0xFF9AA3AD.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()

        /** Pede ao sistema para redesenhar o bloco (quando o celular manda estado novo). */
        fun refresh(context: android.content.Context) {
            runCatching { getUpdater(context).requestUpdate(ApexTileService::class.java) }
        }
    }
}
