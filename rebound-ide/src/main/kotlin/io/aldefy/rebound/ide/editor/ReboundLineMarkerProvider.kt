package io.aldefy.rebound.ide.editor

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import io.aldefy.rebound.ide.data.ReboundSettings
import org.jetbrains.kotlin.psi.KtNamedFunction
import javax.swing.Icon

class ReboundLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (!ReboundSettings.getInstance().state.showGutterIcons) return null

        val function = element.parent as? KtNamedFunction ?: return null
        if (element != function.nameIdentifier) return null
        if (function.annotationEntries.none { it.shortName?.asString() == "Composable" }) return null

        val fqn = function.fqName?.asString() ?: return null
        val store = ReboundSessionStoreHolder.getInstance(element.project).sessionStore ?: return null
        val entry = store.currentEntries[fqn] ?: return null

        val rate = entry.rate
        val budget = entry.budget

        val isOver = rate > budget && budget > 0
        val isNear = !isOver && budget > 0 && rate.toDouble() / budget.toDouble() >= 0.7

        val icon: Icon = when {
            isOver -> AllIcons.General.BalloonError
            isNear -> AllIcons.General.BalloonWarning
            rate > 0 -> AllIcons.General.BalloonInformation
            else -> return null
        }

        val tooltip = buildString {
            append("Rebound: ${rate}/s (budget: ${budget}/s)")
            if (isOver) append(" — OVER BUDGET")
            if (entry.skipPercent >= 0) append(" | skip: ${entry.skipPercent}%")
        }

        return LineMarkerInfo(
            element,
            element.textRange,
            icon,
            { tooltip },
            null,
            GutterIconRenderer.Alignment.RIGHT,
            { tooltip }
        )
    }
}
