package io.aldefy.rebound.ide.editor

import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.settings.CodeVisionGroupSettingProvider
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.hints.codeVision.DaemonBoundCodeVisionProvider
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import io.aldefy.rebound.ide.data.ReboundSettings
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Shows live recomposition metrics (rate, budget, status, skip%) above @Composable functions
 * using IntelliJ's Code Vision (CodeLens-style) API.
 *
 * Example lens text: ▸ 12/s | budget: 8/s | OVER | skip: 45%
 */
class ReboundCodeVisionProvider : DaemonBoundCodeVisionProvider {

    companion object {
        const val ID = "rebound.metrics"
        const val GROUP_ID = "rebound.metrics"
    }

    override val id: String = ID
    override val name: String = "Rebound Metrics"
    override val defaultAnchor: CodeVisionAnchorKind = CodeVisionAnchorKind.Top
    override val relativeOrderings: List<CodeVisionRelativeOrdering> = emptyList()
    override val groupId: String = GROUP_ID

    override fun computeForEditor(
        editor: Editor,
        file: PsiFile
    ): List<Pair<TextRange, CodeVisionEntry>> {
        if (!ReboundSettings.getInstance().state.showInlayHints) return emptyList()

        val project = editor.project ?: return emptyList()
        val store = ReboundSessionStoreHolder.getInstance(project).sessionStore
            ?: return emptyList()

        if (store.currentEntries.isEmpty()) return emptyList()

        val lenses = mutableListOf<Pair<TextRange, CodeVisionEntry>>()

        file.collectDescendantsOfType<KtNamedFunction>().forEach { function ->
            if (function.annotationEntries.none {
                    it.shortName?.asString() == "Composable"
                }) return@forEach

            val fqn = function.fqName?.asString() ?: return@forEach
            val entry = store.currentEntries[fqn] ?: return@forEach

            val isOver = entry.rate > entry.budget && entry.budget > 0
            val text = buildString {
                append("\u25b8 ${entry.rate}/s")
                if (entry.budget > 0) append(" | budget: ${entry.budget}/s")
                if (isOver) append(" | OVER")
                if (entry.skipPercent >= 0) append(" | skip: ${"%.0f".format(entry.skipPercent)}%")
            }

            val range = function.nameIdentifier?.textRange ?: function.textRange
            val visionEntry = ClickableTextCodeVisionEntry(
                text,
                id,
                { _, _ -> },   // no-op on click
                null,           // icon
                text,           // tooltip
                text            // longPresentation
            )
            lenses.add(range to visionEntry)
        }

        return lenses
    }
}

/**
 * Registers the Rebound metrics group in IntelliJ's Code Vision settings panel
 * (Settings > Editor > Inlay Hints > Code Vision).
 */
class ReboundCodeVisionSettingsProvider : CodeVisionGroupSettingProvider {
    override val groupId: String = ReboundCodeVisionProvider.GROUP_ID
    override val groupName: String = "Rebound Metrics"
    override val description: String =
        "Shows live recomposition rate, budget, and status above @Composable functions"
}
